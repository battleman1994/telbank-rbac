package com.telbank.rbac.security;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.testutil.TestProxies;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB 优先的开放接口 AppKey 注册表（A17）：仅启用系统、secret 非空时返回凭据；
 * 查询条件带 appKey + status=1（YAML clients 仅作 fallback，由过滤器测试覆盖）。
 */
class SystemAppKeyRegistryTest {

    @Test
    void resolvesEnabledSystemCredentialFromDatabase() {
        SysSystem system = system("crm", "crm", "db-crm-secret", 1);
        java.util.concurrent.atomic.AtomicInteger selectOneCalls = new java.util.concurrent.atomic.AtomicInteger();
        SysSystemMapper mapper = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> {
                    if ("selectOne".equals(method.getName())) {
                        selectOneCalls.incrementAndGet();
                        return system;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });

        Optional<SystemAppKeyRegistry.AppKeyCredential> credential =
                new SystemAppKeyRegistry(mapper).resolve("crm");

        assertThat(credential).isPresent();
        assertThat(credential.get().appKey()).isEqualTo("crm");
        assertThat(credential.get().systemCode()).isEqualTo("crm");
        assertThat(credential.get().appSecret()).isEqualTo("db-crm-secret");
        assertThat(credential.get().enabled()).isTrue();

        assertThat(selectOneCalls.get()).isEqualTo(1);
    }

    @Test
    void blankAppKeyReturnsEmpty() {
        SysSystemMapper mapper = TestProxies.noop(SysSystemMapper.class);

        assertThat(new SystemAppKeyRegistry(mapper).resolve(null)).isEmpty();
        assertThat(new SystemAppKeyRegistry(mapper).resolve(" ")).isEmpty();
    }

    @Test
    void disabledOrSecretlessSystemIsReturnedButMarkedInactive() {
        SysSystemMapper mapper = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> {
                    if ("selectOne".equals(method.getName())) {
                        return system("crm", "crm", null, 0);
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });

        Optional<SystemAppKeyRegistry.AppKeyCredential> credential =
                new SystemAppKeyRegistry(mapper).resolve("crm");
        assertThat(credential).isPresent();
        assertThat(credential.get().enabled()).isFalse();
        assertThat(credential.get().appSecret()).isNull();
    }

    @Test
    void nonDatabaseLookupsAreNotDoneWhenSystemMissing() {
        SysSystemMapper mapper = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> {
                    if ("selectOne".equals(method.getName())) {
                        return null;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });

        assertThat(new SystemAppKeyRegistry(mapper).resolve("unknown")).isEmpty();
    }

    private static SysSystem system(String code, String appKey, String appSecret, int status) {
        SysSystem system = new SysSystem();
        system.setId(1L);
        system.setCode(code);
        system.setName(code);
        system.setRegion("region_a");
        system.setStatus(status);
        system.setIsPlatform(0);
        system.setAppKey(appKey);
        system.setAppSecret(appSecret);
        return system;
    }
}
