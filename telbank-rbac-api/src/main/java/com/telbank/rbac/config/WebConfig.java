package com.telbank.rbac.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;


/**
 * Web 配置：CORS（契约 §1.8：开发环境允许 5173，生产按 {@code rbac.cors.allowed-origins} 配置）。
 *
 * <p>时间序列化口径由 Jackson 3 默认行为保证：java.time 类型（Instant 等）输出 ISO-8601 字符串，
 * 不做 epoch 数字转换（契约 §1.4）。</p>
 */
@Configuration
public class WebConfig {

    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD");
    private static final List<String> ALLOWED_HEADERS = List.of("*");

    @Bean
    public CorsConfigurationSource corsConfigurationSource(RbacProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> allowedOrigins = properties.getCors().getAllowedOrigins();
        configuration.setAllowedOrigins(allowedOrigins == null ? List.of() : allowedOrigins);
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        configuration.setExposedHeaders(List.of("Content-Type"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
