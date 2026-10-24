package com.telbank.rbac.sdk.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.telbank.rbac.sdk.annotation.RbacPermission;
import com.telbank.rbac.sdk.client.EndpointInfo;
import com.telbank.rbac.sdk.client.RbacRemoteException;
import com.telbank.rbac.sdk.config.RbacSdkProperties;
import com.telbank.rbac.sdk.support.StubRbacOpenApiClient;

/**
 * 端点扫描：跳过 @PreAuthorize、注解候选码优先、按规则生成候选码、base-packages 过滤、上报失败不抛出。
 */
class RbacEndpointScannerTest {

    @Test
    void scansEndpointsWithAnnotationPriorityAndSecuritySkips() throws Exception {
        RbacEndpointScanner scanner = scanner(properties());

        List<EndpointInfo> endpoints = scanner.scanEndpoints();

        assertThat(endpoints).hasSize(3);
        Map<String, EndpointInfo> byPath = indexByPath(endpoints);
        assertThat(byPath.get("/api/order/{id}").getCandidateCode()).isEqualTo("crm:demo:delete");
        assertThat(byPath.get("/api/order/{id}").getSource()).isEqualTo(EndpointInfo.SOURCE_GENERATED);
        assertThat(byPath.get("/api/order/list").getCandidateCode()).isEqualTo("crm:demo:list");
        assertThat(byPath.get("/api/order/annotated").getCandidateCode()).isEqualTo("custom:code");
        assertThat(byPath.get("/api/order/annotated").getSource())
                .isEqualTo(EndpointInfo.SOURCE_ANNOTATION);
        // 带 @PreAuthorize 的端点完全跳过
        assertThat(byPath).doesNotContainKey("/api/order/secured");
    }

    @Test
    void reportsScannedEndpoints() throws Exception {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        RbacEndpointScanner scanner = scanner(properties(), remote);

        List<EndpointInfo> endpoints = scanner.scanEndpoints();
        scanner.reportEndpoints(endpoints);

        assertThat(remote.syncCalls.get()).isEqualTo(1);
        assertThat(remote.syncedEndpoints).hasSize(3);
    }

    @Test
    void reportFailureOnlyWarns() throws Exception {
        StubRbacOpenApiClient remote = new StubRbacOpenApiClient();
        remote.failOnSync = new RbacRemoteException("permission center down");
        RbacEndpointScanner scanner = scanner(properties(), remote);

        scanner.reportEndpoints(scanner.scanEndpoints());

        assertThat(remote.syncCalls.get()).isEqualTo(1);
    }

    @Test
    void basePackagesFilterSkipsControllersOutsidePackages() throws Exception {
        RbacSdkProperties properties = properties();
        properties.getScan().setBasePackages(Collections.singletonList("com.telbank.other"));
        RbacEndpointScanner scanner = scanner(properties);

        assertThat(scanner.scanEndpoints()).isEmpty();
    }

    private static Map<String, EndpointInfo> indexByPath(List<EndpointInfo> endpoints) {
        Map<String, EndpointInfo> result = new LinkedHashMap<String, EndpointInfo>();
        for (EndpointInfo endpoint : endpoints) {
            result.put(endpoint.getPath(), endpoint);
        }
        return result;
    }

    private static RbacEndpointScanner scanner(RbacSdkProperties properties) throws Exception {
        return scanner(properties, new StubRbacOpenApiClient());
    }

    private static RbacEndpointScanner scanner(RbacSdkProperties properties,
            StubRbacOpenApiClient remote) throws Exception {
        DemoController controller = new DemoController();
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<RequestMappingInfo, HandlerMethod>();
        handlers.put(info("/api/order/{id}", RequestMethod.DELETE), handler(controller, "deleteOrder"));
        handlers.put(info("/api/order/list", RequestMethod.GET), handler(controller, "list"));
        handlers.put(info("/api/order/annotated", RequestMethod.POST), handler(controller, "annotated"));
        handlers.put(info("/api/order/secured", RequestMethod.GET), handler(controller, "secured"));

        RequestMappingHandlerMapping handlerMapping = mock(RequestMappingHandlerMapping.class);
        when(handlerMapping.getHandlerMethods()).thenReturn(handlers);
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBeansOfType(RequestMappingHandlerMapping.class))
                .thenReturn(Collections.singletonMap("requestMappingHandlerMapping", handlerMapping));
        return new RbacEndpointScanner(applicationContext, properties, remote,
                new RbacCodeGenerator(properties));
    }

    private static RequestMappingInfo info(String path, RequestMethod method) {
        return RequestMappingInfo.paths(path).methods(method).build();
    }

    private static HandlerMethod handler(Object bean, String methodName) throws NoSuchMethodException {
        return new HandlerMethod(bean, bean.getClass().getMethod(methodName));
    }

    private static RbacSdkProperties properties() {
        RbacSdkProperties properties = new RbacSdkProperties();
        properties.setEnabled(true);
        properties.setSystemCode("crm");
        return properties;
    }

    static class DemoController {

        public void deleteOrder() {
        }

        public void list() {
        }

        @RbacPermission("custom:code")
        public void annotated() {
        }

        @PreAuthorize("hasRole('ADMIN')")
        public void secured() {
        }
    }
}
