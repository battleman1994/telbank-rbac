package com.telbank.rbac.sdk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明接口对应的权限码（需求 §6.6）。
 *
 * <p>SDK 端点扫描时，命中该注解的端点使用 {@link #value()} 作为候选权限码（方法级优先于类级），
 * 并覆盖 code-generator 自动生成规则。这是 SDK 唯一正式承诺解析的注解，不解析 Spring Security 注解。</p>
 *
 * <pre>
 * &#64;RbacPermission("order:delete")
 * &#64;DeleteMapping("/api/order/{id}")
 * public void deleteOrder(@PathVariable Long id) { ... }
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RbacPermission {

    /**
     * 权限码，如 {@code order:delete}。
     */
    String value();

    /**
     * 权限名称（仅上报展示用，可为空）。
     */
    String name() default "";

    /**
     * 是否为公共权限（仅上报展示用，运行期以权限中心数据为准）。
     */
    boolean isPublic() default false;
}
