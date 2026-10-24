package com.telbank.rbac.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.telbank.rbac.sdk.cache.RbacCacheRefresher;
import com.telbank.rbac.sdk.cache.RbacMappingCache;
import com.telbank.rbac.sdk.cache.RbacPageActionCache;
import com.telbank.rbac.sdk.cache.RbacUserPermissionCache;
import com.telbank.rbac.sdk.client.DefaultRbacClient;
import com.telbank.rbac.sdk.client.HttpRbacOpenApiClient;
import com.telbank.rbac.sdk.client.RbacClient;
import com.telbank.rbac.sdk.client.RbacOpenApiClient;
import com.telbank.rbac.sdk.client.RbacSigner;
import com.telbank.rbac.sdk.config.RbacSdkProperties;
import com.telbank.rbac.sdk.filter.RbacPermissionFilter;
import com.telbank.rbac.sdk.notify.RbacChangeSubscriber;
import com.telbank.rbac.sdk.notify.RbacPollingFallback;
import com.telbank.rbac.sdk.scan.RbacCodeGenerator;
import com.telbank.rbac.sdk.scan.RbacEndpointScanner;
import com.telbank.rbac.sdk.web.RbacController;

/**
 * telbank-rbac SDK 唯一自动配置入口（JDK 21 / Spring Boot 4.x，
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 单轨注册）。
 *
 * <p>总开关 {@code rbac-sdk.enabled=true} 时一次性注册整套权限校验逻辑；
 * {@code false}（默认）时全部组件不注册。不提供细粒度开关。</p>
 *
 * <p>组件注册：</p>
 * <ul>
 *   <li>核心：签名器、开放接口客户端、映射缓存、用户权限集缓存、页面元数据缓存、缓存刷新器、
 *       兜底短轮询、公开 {@link RbacClient}（唯一允许业务方用同类型 Bean 覆盖的扩展点）；</li>
 *   <li>Web（Servlet 环境）：{@link RbacPermissionFilter}（FilterRegistrationBean /*，
 *       order=LOWEST_PRECEDENCE-100）、{@link RbacController}、{@link RbacEndpointScanner}；</li>
 *   <li>Redis（classpath 存在 spring-data-redis 时）：{@link RbacChangeSubscriber}；
 *       缺失时自动跳过，只保留兜底轮询。</li>
 * </ul>
 *
 * <p>所有远端依赖（权限中心、Redis）在启动期失败都只告警降级，不得导致宿主启动失败。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "rbac-sdk", name = "enabled", havingValue = "true")
@ConditionalOnClass(name = "tools.jackson.databind.ObjectMapper")
@EnableConfigurationProperties(RbacSdkProperties.class)
public class RbacSdkAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RbacSdkAutoConfiguration.class);

    public RbacSdkAutoConfiguration(RbacSdkProperties properties) {
        // 快速失败：enabled=true 但关键配置缺失时明确报错，避免“已开启却静默放行所有请求”
        properties.validate();
        log.info("telbank-rbac SDK 已启用: systemCode={}, platform={}, basePath={}", properties.getSystemCode(),
                properties.getPlatformBaseUrl(), properties.getEndpoint().getBasePath());
    }

    @Bean
    public RbacSigner rbacSigner(RbacSdkProperties properties) {
        return new RbacSigner(properties.getAppKey(), properties.getAppSecret());
    }

    @Bean
    public RbacOpenApiClient rbacOpenApiClient(RbacSdkProperties properties, RbacSigner rbacSigner) {
        return new HttpRbacOpenApiClient(properties, rbacSigner);
    }

    @Bean
    public RbacMappingCache rbacMappingCache() {
        return new RbacMappingCache();
    }

    @Bean
    public RbacPageActionCache rbacPageActionCache() {
        return new RbacPageActionCache();
    }

    @Bean
    public RbacUserPermissionCache rbacUserPermissionCache(RbacOpenApiClient rbacOpenApiClient) {
        return new RbacUserPermissionCache(rbacOpenApiClient);
    }

    @Bean
    public RbacCacheRefresher rbacCacheRefresher(RbacOpenApiClient rbacOpenApiClient,
            RbacMappingCache rbacMappingCache, RbacPageActionCache rbacPageActionCache) {
        return new RbacCacheRefresher(rbacOpenApiClient, rbacMappingCache, rbacPageActionCache);
    }

    @Bean
    public RbacPollingFallback rbacPollingFallback(RbacCacheRefresher rbacCacheRefresher,
            RbacSdkProperties properties) {
        return new RbacPollingFallback(rbacCacheRefresher, properties);
    }

    /**
     * 公开的编程式查询 API。业务系统声明自己的 {@link RbacClient} Bean 即可覆盖默认实现
     * （SDK 内部校验逻辑不依赖该 Bean，因此覆盖不影响拦截器）。
     */
    @Bean
    @ConditionalOnMissingBean(RbacClient.class)
    public RbacClient rbacClient(RbacOpenApiClient rbacOpenApiClient, RbacSdkProperties properties,
            RbacUserPermissionCache rbacUserPermissionCache, RbacPageActionCache rbacPageActionCache,
            RbacCacheRefresher rbacCacheRefresher) {
        return new DefaultRbacClient(rbacOpenApiClient, properties, rbacUserPermissionCache,
                rbacPageActionCache, rbacCacheRefresher);
    }

    /**
     * Servlet 环境组件：拦截器 / 查询接口 / 端点扫描。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = {"jakarta.servlet.Filter", "org.springframework.web.servlet.DispatcherServlet"})
    static class RbacSdkWebConfiguration {

        @Bean
        public FilterRegistrationBean<RbacPermissionFilter> rbacPermissionFilterRegistration(
                RbacSdkProperties properties, RbacMappingCache rbacMappingCache,
                RbacUserPermissionCache rbacUserPermissionCache) {
            RbacPermissionFilter filter = new RbacPermissionFilter(properties, rbacMappingCache,
                    rbacUserPermissionCache);
            FilterRegistrationBean<RbacPermissionFilter> registration =
                    new FilterRegistrationBean<RbacPermissionFilter>(filter);
            registration.setName("rbacPermissionFilter");
            registration.addUrlPatterns("/*");
            // 契约 §7.1：排在 Spring Security 之后
            registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
            return registration;
        }

        @Bean
        public RbacController rbacController(RbacSdkProperties properties, RbacOpenApiClient rbacOpenApiClient,
                RbacUserPermissionCache rbacUserPermissionCache, RbacPageActionCache rbacPageActionCache) {
            return new RbacController(properties, rbacOpenApiClient, rbacUserPermissionCache,
                    rbacPageActionCache);
        }

        @Bean
        public RbacCodeGenerator rbacCodeGenerator(RbacSdkProperties properties) {
            return new RbacCodeGenerator(properties);
        }

        @Bean
        public RbacEndpointScanner rbacEndpointScanner(ApplicationContext applicationContext,
                RbacSdkProperties properties, RbacOpenApiClient rbacOpenApiClient,
                RbacCodeGenerator rbacCodeGenerator) {
            return new RbacEndpointScanner(applicationContext, properties, rbacOpenApiClient,
                    rbacCodeGenerator);
        }
    }

    /**
     * Redis Pub/Sub 组件：仅当宿主 classpath 存在 spring-data-redis + Lettuce 时注册；
     * 连接失败在运行期降级为兜底轮询。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
            "org.springframework.data.redis.connection.RedisConnectionFactory",
            "org.springframework.data.redis.listener.RedisMessageListenerContainer",
            "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory",
            "io.lettuce.core.RedisClient"})
    static class RbacSdkRedisConfiguration {

        @Bean
        public RbacChangeSubscriber rbacChangeSubscriber(RbacSdkProperties properties,
                RbacOpenApiClient rbacOpenApiClient, RbacCacheRefresher rbacCacheRefresher,
                RbacUserPermissionCache rbacUserPermissionCache) {
            return new RbacChangeSubscriber(properties, rbacOpenApiClient, rbacCacheRefresher,
                    rbacUserPermissionCache);
        }
    }
}
