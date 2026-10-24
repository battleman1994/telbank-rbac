package com.telbank.rbac.sdk.scan;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.telbank.rbac.sdk.config.RbacSdkProperties;

/**
 * 候选权限码生成器（需求 §6.7，契约 §7.3）。
 *
 * <p>默认规则 {@code {systemCode}:{resource}:{action}}：</p>
 * <ul>
 *   <li>{@code resource} = Controller 简单类名去掉 {@code Controller} 后缀后转 lower-kebab
 *       （{@code OrderController} → {@code order}，{@code OrderItemController} → {@code order-item}）；</li>
 *   <li>{@code action} = HTTP 方法映射：GET→list、POST→create、PUT→update、DELETE→delete、PATCH→patch，
 *       其他方法取小写方法名。</li>
 * </ul>
 *
 * <p>示例：{@code OrderController.deleteOrder + DELETE → crm:order:delete}。</p>
 */
public class RbacCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(RbacCodeGenerator.class);

    private static final String DEFAULT_PATTERN = "{systemCode}:{resource}:{action}";

    private static final String CONTROLLER_SUFFIX = "Controller";

    private static final String RESOURCE_FROM_CONTROLLER_CLASS = "controller-class";

    private static final String ACTION_FROM_HTTP_METHOD = "http-method";

    private final RbacSdkProperties.CodeGenerator config;

    public RbacCodeGenerator(RbacSdkProperties properties) {
        if (properties == null) {
            throw new IllegalArgumentException("properties 不能为空");
        }
        this.config = properties.getCodeGenerator();
        String resourceFrom = config.getResourceFrom();
        if (resourceFrom != null && !RESOURCE_FROM_CONTROLLER_CLASS.equalsIgnoreCase(resourceFrom.trim())) {
            log.warn("rbac-sdk.code-generator.resource-from={} 暂不支持，回退为 controller-class", resourceFrom);
        }
        String actionFrom = config.getActionFrom();
        if (actionFrom != null && !ACTION_FROM_HTTP_METHOD.equalsIgnoreCase(actionFrom.trim())) {
            log.warn("rbac-sdk.code-generator.action-from={} 暂不支持，回退为 http-method", actionFrom);
        }
    }

    /**
     * 生成候选权限码。
     *
     * @param systemCode     业务系统编码
     * @param controllerType Controller 类型
     * @param httpMethod     HTTP 方法
     * @return 候选权限码（非空）
     */
    public String generate(String systemCode, Class<?> controllerType, String httpMethod) {
        String pattern = config.getPattern();
        if (pattern == null || pattern.trim().isEmpty()) {
            pattern = DEFAULT_PATTERN;
        }
        String resource = resourceName(controllerType);
        String action = actionName(httpMethod);
        return pattern.replace("{systemCode}", systemCode == null ? "" : systemCode)
                .replace("{resource}", resource)
                .replace("{action}", action);
    }

    /**
     * Controller 简单类名 → lower-kebab 资源名：{@code OrderItemController → order-item}。
     */
    public static String resourceName(Class<?> controllerType) {
        if (controllerType == null) {
            return "api";
        }
        String simpleName = controllerType.getSimpleName();
        if (simpleName == null || simpleName.isEmpty()) {
            return "api";
        }
        if (simpleName.length() > CONTROLLER_SUFFIX.length() && simpleName.endsWith(CONTROLLER_SUFFIX)) {
            simpleName = simpleName.substring(0, simpleName.length() - CONTROLLER_SUFFIX.length());
        }
        String kebab = camelToKebab(simpleName);
        return kebab.isEmpty() ? "api" : kebab;
    }

    /**
     * HTTP 方法 → 动作名：GET→list、POST→create、PUT→update、DELETE→delete、PATCH→patch。
     */
    public static String actionName(String httpMethod) {
        if (httpMethod == null) {
            return "unknown";
        }
        String method = httpMethod.trim().toUpperCase(Locale.ROOT);
        if ("GET".equals(method)) {
            return "list";
        }
        if ("POST".equals(method)) {
            return "create";
        }
        if ("PUT".equals(method)) {
            return "update";
        }
        if ("DELETE".equals(method)) {
            return "delete";
        }
        if ("PATCH".equals(method)) {
            return "patch";
        }
        return method.toLowerCase(Locale.ROOT);
    }

    /**
     * 驼峰转 lower-kebab（处理连续大写缩写）：{@code OrderItem → order-item}、{@code URLPath → url-path}。
     */
    public static String camelToKebab(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isUpperCase(current)) {
                boolean previousLowerOrDigit = i > 0
                        && (Character.isLowerCase(value.charAt(i - 1)) || Character.isDigit(value.charAt(i - 1)));
                boolean previousUpper = i > 0 && Character.isUpperCase(value.charAt(i - 1));
                boolean nextLower = i + 1 < value.length() && Character.isLowerCase(value.charAt(i + 1));
                if (i > 0 && (previousLowerOrDigit || (previousUpper && nextLower))) {
                    builder.append('-');
                }
                builder.append(Character.toLowerCase(current));
            } else {
                builder.append(current);
            }
        }
        return builder.toString();
    }
}
