package com.telbank.rbac.security;

import java.lang.reflect.Method;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.telbank.rbac.common.PermissionMatcher;
import com.telbank.rbac.web.admin.SystemCredentialController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 系统凭据接口契约（A18）：current 需 {@code system:credential}（未落种子码，靠通配/平台超管短路），
 * by-id 仅平台超管；路由固定。
 */
class SystemCredentialControllerContractTest {

    @Test
    void currentRequiresSystemCredentialPermissionCode() throws NoSuchMethodException {
        RequestMapping classMapping = SystemCredentialController.class.getAnnotation(RequestMapping.class);
        assertThat(classMapping.value()).containsExactly("/api/system-credentials");

        Method current = SystemCredentialController.class.getMethod("current");
        PreAuthorize preAuthorize = current.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("@perm.has('system:credential')");
        assertThat(current.getAnnotation(GetMapping.class).value()).containsExactly("/current");
    }

    @Test
    void byIdRequiresPlatformAdmin() throws NoSuchMethodException {
        Method byId = SystemCredentialController.class.getMethod("bySystemId", Long.class);
        PreAuthorize preAuthorize = byId.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("@perm.isPlatformAdmin()");
        assertThat(byId.getAnnotation(GetMapping.class).value()).containsExactly("/{systemId}");
    }

    @Test
    void systemCredentialCodeMatchesWildcardWithoutSeedRecord() {
        // admin 角色只有一条 '*'，无需为 system:credential 落种子记录即可通过断言
        assertThat(PermissionMatcher.matches(Set.of("*"), "system:credential")).isTrue();
        assertThat(PermissionMatcher.matches(Set.of("system:credential"), "system:credential")).isTrue();
        assertThat(PermissionMatcher.matches(Set.of("order:*", "role:manage"), "system:credential")).isFalse();
    }
}
