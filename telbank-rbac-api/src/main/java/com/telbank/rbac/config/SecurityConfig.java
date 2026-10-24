package com.telbank.rbac.config;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import com.telbank.rbac.security.AppKeyAuthFilter;
import com.telbank.rbac.security.JsonAccessDeniedHandler;
import com.telbank.rbac.security.JsonAuthenticationEntryPoint;
import com.telbank.rbac.security.ManagementContextFilter;
import com.telbank.rbac.security.ManagementContextService;
import com.telbank.rbac.security.SystemAppKeyRegistry;
import com.telbank.rbac.security.NonceStore;

import tools.jackson.databind.json.JsonMapper;

/**
 * 安全配置：OAuth2 Resource Server + 方法级授权 + 开放接口 AppKey 验签过滤器（契约 §1.5 / §4.1）。
 *
 * <p>权限中心只校验外部签发的 JWT（sub = external_user_id），不签发、无登录页。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final int APP_KEY_FILTER_ORDER = 0;
    private static final int MANAGEMENT_CONTEXT_FILTER_ORDER = -40;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JsonAuthenticationEntryPoint authenticationEntryPoint,
                                                   JsonAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/openapi/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * JwtDecoder：{@code rbac.security.jwt.mode=jwk|hs256} 切换（契约 §8.1 / §1.10）。
     */
    @Bean
    public JwtDecoder jwtDecoder(RbacProperties properties) {
        RbacProperties.Jwt jwtProperties = properties.getSecurity().getJwt();
        String mode = jwtProperties.getMode() == null ? "jwk" : jwtProperties.getMode().trim().toLowerCase();
        return switch (mode) {
            case "hs256" -> hs256Decoder(jwtProperties);
            case "jwk" -> jwkDecoder(jwtProperties);
            default -> throw new IllegalStateException(
                    "unsupported rbac.security.jwt.mode: " + jwtProperties.getMode() + " (expected jwk or hs256)");
        };
    }

    private JwtDecoder hs256Decoder(RbacProperties.Jwt jwtProperties) {
        String secret = jwtProperties.getHs256Secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("rbac.security.jwt.hs256-secret must be configured when mode=hs256");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("rbac.security.jwt.hs256-secret must be at least 32 bytes for HS256");
        }
        SecretKey key = new SecretKeySpec(secretBytes, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(validators(jwtProperties));
        log.info("rbac jwt decoder initialized with mode=hs256");
        return decoder;
    }

    private JwtDecoder jwkDecoder(RbacProperties.Jwt jwtProperties) {
        String jwkSetUri = jwtProperties.getJwkSetUri();
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            throw new IllegalStateException(
                    "rbac.security.jwt.jwk-set-uri must be configured when mode=jwk");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(validators(jwtProperties));
        log.info("rbac jwt decoder initialized with mode=jwk, jwkSetUri={}", jwkSetUri);
        return decoder;
    }

    /**
     * 默认校验签名与 exp/nbf；配置了 issuer 时额外校验 iss（demo 模式 iss=demo-auth）。
     */
    private OAuth2TokenValidator<Jwt> validators(RbacProperties.Jwt jwtProperties) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator());
        String issuer = jwtProperties.getIssuer();
        if (issuer != null && !issuer.isBlank()) {
            validators.add(new JwtClaimValidator<>("iss", claim -> issuer.equals(claim)));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    /**
     * 将管理端系统上下文过滤器注册为 Servlet Filter（{@code /api/**}，在 Spring Security 之后执行）。
     */
    @Bean
    public FilterRegistrationBean<ManagementContextFilter> managementContextFilterRegistration(
            ManagementContextService managementContextService, JsonMapper jsonMapper) {
        FilterRegistrationBean<ManagementContextFilter> registration = new FilterRegistrationBean<>(
                new ManagementContextFilter(managementContextService, jsonMapper));
        registration.setName("rbacManagementContextFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(MANAGEMENT_CONTEXT_FILTER_ORDER);
        return registration;
    }

    /**
     * 将 {@code /openapi/**} 的 AppKey 验签过滤器注册为 Servlet Filter（在 Spring Security 之后执行）。
     */
    @Bean
    public FilterRegistrationBean<AppKeyAuthFilter> appKeyAuthFilterRegistration(
            JsonMapper jsonMapper, RbacProperties properties, NonceStore nonceStore,
            SystemAppKeyRegistry appKeyRegistry) {
        FilterRegistrationBean<AppKeyAuthFilter> registration = new FilterRegistrationBean<>(
                new AppKeyAuthFilter(jsonMapper, properties, nonceStore, appKeyRegistry));
        registration.setName("rbacAppKeyAuthFilter");
        registration.addUrlPatterns("/*");
        registration.setOrder(APP_KEY_FILTER_ORDER);
        return registration;
    }
}
