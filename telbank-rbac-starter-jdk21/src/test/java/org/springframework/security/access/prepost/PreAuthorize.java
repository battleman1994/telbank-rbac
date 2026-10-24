package org.springframework.security.access.prepost;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 测试专用桩：仅用于验证 SDK 端点扫描按「类名」跳过 Spring Security 注解，
 * 本工程不依赖 spring-security。不要在任何生产代码中引用。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PreAuthorize {

    String value();
}
