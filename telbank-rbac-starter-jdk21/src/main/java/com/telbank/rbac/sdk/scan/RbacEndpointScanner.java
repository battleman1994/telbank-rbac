package com.telbank.rbac.sdk.scan;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import com.telbank.rbac.sdk.annotation.RbacPermission;
import com.telbank.rbac.sdk.cache.RbacPathMatcher;
import com.telbank.rbac.sdk.client.EndpointInfo;
import com.telbank.rbac.sdk.client.RbacOpenApiClient;
import com.telbank.rbac.sdk.config.RbacSdkProperties;

/**
 * 端点扫描与全量上报（需求 §6.7，契约 §7.3）。
 *
 * <p>监听 {@link ApplicationReadyEvent}，遍历所有 {@link RequestMappingHandlerMapping}：</p>
 * <ul>
 *   <li>跳过方法或类上有 {@code @PreAuthorize}/{@code @Secured}（含组合注解）的端点；</li>
 *   <li>有 {@link RbacPermission} → 候选码 = 注解 {@code value}（方法级优先于类级）；</li>
 *   <li>无注解 → 按 code-generator 规则生成候选码；</li>
 *   <li>全量 {@code POST /openapi/sdk/endpoints/sync}；失败只告警，不影响宿主启动。</li>
 * </ul>
 *
 * <p>扫描与上报均为旁路逻辑：上报在守护线程中执行，权限中心不可达不会阻塞宿主启动。</p>
 */
public class RbacEndpointScanner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(RbacEndpointScanner.class);

    private static final String PRE_AUTHORIZE_ANNOTATION =
            "org.springframework.security.access.prepost.PreAuthorize";

    private static final String SECURED_ANNOTATION =
            "org.springframework.security.access.annotation.Secured";

    private final ApplicationContext applicationContext;

    private final RbacSdkProperties properties;

    private final RbacOpenApiClient remoteClient;

    private final RbacCodeGenerator codeGenerator;

    public RbacEndpointScanner(ApplicationContext applicationContext, RbacSdkProperties properties,
            RbacOpenApiClient remoteClient, RbacCodeGenerator codeGenerator) {
        if (applicationContext == null || properties == null || remoteClient == null
                || codeGenerator == null) {
            throw new IllegalArgumentException("RbacEndpointScanner 依赖不能为空");
        }
        this.applicationContext = applicationContext;
        this.properties = properties;
        this.remoteClient = remoteClient;
        this.codeGenerator = codeGenerator;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!properties.getScan().isReportOnStartup()) {
            log.info("rbac-sdk.scan.report-on-startup=false，跳过端点扫描上报");
            return;
        }
        List<EndpointInfo> endpoints;
        try {
            endpoints = scanEndpoints();
        } catch (Throwable t) {
            log.warn("RBAC 端点扫描失败（不影响宿主启动）: {}", t.toString(), t);
            return;
        }
        if (endpoints.isEmpty()) {
            log.info("RBAC 端点扫描完成：没有需要上报的端点");
            return;
        }
        log.info("RBAC 端点扫描完成，共 {} 个端点，开始异步全量上报", endpoints.size());
        Thread reporter = new Thread(new Runnable() {
            @Override
            public void run() {
                reportEndpoints(endpoints);
            }
        }, "rbac-sdk-endpoint-report");
        reporter.setDaemon(true);
        reporter.start();
    }

    /**
     * 扫描全部 RequestMappingHandlerMapping，返回去重后的端点列表（method + path 维度）。
     */
    public List<EndpointInfo> scanEndpoints() {
        Map<String, EndpointInfo> endpoints = new LinkedHashMap<String, EndpointInfo>();
        Map<String, RequestMappingHandlerMapping> handlerMappings =
                applicationContext.getBeansOfType(RequestMappingHandlerMapping.class);
        for (RequestMappingHandlerMapping handlerMapping : handlerMappings.values()) {
            Map<RequestMappingInfo, HandlerMethod> handlerMethods;
            try {
                handlerMethods = handlerMapping.getHandlerMethods();
            } catch (RuntimeException e) {
                log.warn("读取 RequestMappingHandlerMapping 失败，跳过: {}", e.toString());
                continue;
            }
            if (handlerMethods == null) {
                continue;
            }
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
                try {
                    scanHandler(entry.getKey(), entry.getValue(), endpoints);
                } catch (RuntimeException e) {
                    log.warn("扫描端点失败，跳过 {}: {}", entry.getKey(), e.toString());
                }
            }
        }
        return new ArrayList<EndpointInfo>(endpoints.values());
    }

    /**
     * 上报端点（失败只告警）。
     */
    public void reportEndpoints(List<EndpointInfo> endpoints) {
        if (endpoints == null || endpoints.isEmpty()) {
            return;
        }
        try {
            remoteClient.syncEndpoints(endpoints);
            log.info("RBAC 端点上报成功: {} 个端点", endpoints.size());
        } catch (Throwable t) {
            log.warn("RBAC 端点上报失败（不影响宿主启动）: {}", t.toString());
        }
    }

    private void scanHandler(RequestMappingInfo mappingInfo, HandlerMethod handlerMethod,
            Map<String, EndpointInfo> endpoints) {
        if (handlerMethod == null) {
            return;
        }
        Method method = handlerMethod.getMethod();
        Class<?> beanType = handlerMethod.getBeanType();
        if (hasSecurityAnnotation(method) || hasSecurityAnnotation(beanType)) {
            log.debug("跳过带 Spring Security 注解的端点: {}.{}", beanType.getSimpleName(), method.getName());
            return;
        }
        if (!isInBasePackages(beanType)) {
            return;
        }
        List<String> patterns = extractPatterns(mappingInfo);
        if (patterns.isEmpty()) {
            return;
        }
        Set<RequestMethod> httpMethods = mappingInfo.getMethodsCondition().getMethods();
        if (httpMethods.isEmpty()) {
            log.debug("跳过未限定 HTTP Method 的端点（无法生成 action）: {}", patterns);
            return;
        }
        RbacPermission annotation = findRbacPermission(method, beanType);
        for (RequestMethod httpMethod : httpMethods) {
            for (String pattern : patterns) {
                String path = RbacPathMatcher.normalize(pattern);
                String key = httpMethod.name() + ' ' + path;
                if (endpoints.containsKey(key)) {
                    continue;
                }
                String candidateCode;
                String source;
                if (annotation != null && annotation.value() != null
                        && !annotation.value().trim().isEmpty()) {
                    candidateCode = annotation.value().trim();
                    source = EndpointInfo.SOURCE_ANNOTATION;
                } else {
                    candidateCode = codeGenerator.generate(properties.getSystemCode(), beanType,
                            httpMethod.name());
                    source = EndpointInfo.SOURCE_GENERATED;
                }
                endpoints.put(key, new EndpointInfo(httpMethod.name(), path, candidateCode, source));
            }
        }
    }

    private static List<String> extractPatterns(RequestMappingInfo mappingInfo) {
        List<String> patterns = new ArrayList<String>();
        try {
            PatternsRequestCondition patternsCondition = mappingInfo.getPatternsCondition();
            if (patternsCondition != null && patternsCondition.getPatterns() != null) {
                patterns.addAll(patternsCondition.getPatterns());
            }
        } catch (RuntimeException e) {
            // Boot 2.6+ 默认 PathPatternParser 时该条件不可用，下面读取 PathPatternsCondition
            log.debug("读取 PatternsRequestCondition 失败，回退 PathPatternsCondition: {}", e.toString());
        }
        if (patterns.isEmpty()) {
            try {
                org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition pathPatternsCondition =
                        mappingInfo.getPathPatternsCondition();
                if (pathPatternsCondition != null) {
                    for (PathPattern pathPattern : pathPatternsCondition.getPatterns()) {
                        patterns.add(pathPattern.getPatternString());
                    }
                }
            } catch (RuntimeException e) {
                log.debug("读取 PathPatternsCondition 失败: {}", e.toString());
            }
        }
        return patterns;
    }

    private boolean isInBasePackages(Class<?> beanType) {
        List<String> basePackages = properties.getScan().getBasePackages();
        if (basePackages == null || basePackages.isEmpty()) {
            return true;
        }
        Package beanPackage = beanType.getPackage();
        String packageName = beanPackage == null ? "" : beanPackage.getName();
        for (String basePackage : basePackages) {
            if (basePackage == null || basePackage.trim().isEmpty()) {
                continue;
            }
            String base = basePackage.trim();
            if (packageName.equals(base) || packageName.startsWith(base + ".")) {
                return true;
            }
        }
        return false;
    }

    private static RbacPermission findRbacPermission(Method method, Class<?> beanType) {
        RbacPermission annotation = method.getAnnotation(RbacPermission.class);
        if (annotation == null) {
            annotation = beanType.getAnnotation(RbacPermission.class);
        }
        return annotation;
    }

    private static boolean hasSecurityAnnotation(AnnotatedElement element) {
        if (element == null || element.getAnnotations().length == 0) {
            return false;
        }
        for (Annotation annotation : element.getAnnotations()) {
            if (isSecurityAnnotation(annotation)) {
                return true;
            }
        }
        // 组合注解：检查一层 meta-annotation
        for (Annotation annotation : element.getAnnotations()) {
            for (Annotation meta : annotation.annotationType().getAnnotations()) {
                if (isSecurityAnnotation(meta)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSecurityAnnotation(Annotation annotation) {
        String name = annotation.annotationType().getName();
        return PRE_AUTHORIZE_ANNOTATION.equals(name) || SECURED_ANNOTATION.equals(name);
    }
}
