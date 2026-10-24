package com.telbank.rbac.service;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.telbank.rbac.common.ApiException;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;
import com.telbank.rbac.security.CurrentUserProvider;
import com.telbank.rbac.security.ManagementContextService;
import com.telbank.rbac.testutil.SecurityTestSupport;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.SystemCredentialDtos.SystemCredentialVO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 系统接入凭据查看（A18）：current 严格取 X-System-Code 上下文；by-id 平台/不存在 404。
 */
class SystemCredentialServiceTest {

    private static final Long CRM_ID = 1L;
    private static final Long ERP_ID = 2L;
    private static final Long PLATFORM_ID = 4L;

    private SystemCredentialService service;
    private SysSystem crm;
    private SysSystem erp;

    @BeforeEach
    void setUp() {
        crm = system(CRM_ID, "crm", "CRM系统", "region_a", "crm", "crm-dev-secret", 0);
        erp = system(ERP_ID, "erp", "ERP系统", "region_b", "erp", "erp-dev-secret", 0);
        SysSystem platform = system(PLATFORM_ID, "platform", "权限中心", "region_a", null, null, 1);
        Map<Long, SysSystem> byId = Map.of(CRM_ID, crm, ERP_ID, erp, PLATFORM_ID, platform);
        Map<String, SysSystem> byCode = Map.of("crm", crm, "erp", erp);

        SysSystemMapper systemRepository = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> {
                    if ("findByCode".equals(method.getName())) {
                        return Optional.ofNullable(byCode.get((String) args[0]));
                    }
                    if ("findById".equals(method.getName())) {
                        return Optional.ofNullable(byId.get((Long) args[0]));
                    }
                    if ("findFirstByIsPlatformOrderByIdAsc".equals(method.getName())) {
                        return Optional.empty();
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysUserRoleMapper userRoleRepository = TestProxies.proxy(SysUserRoleMapper.class,
                (proxy, method, args) -> {
                    if ("countEffectiveRoles".equals(method.getName())) {
                        Long systemId = (Long) args[0];
                        String userId = (String) args[1];
                        return (CRM_ID.equals(systemId) && "crm-admin-001".equals(userId))
                                || (ERP_ID.equals(systemId) && "erp-admin-001".equals(userId)) ? 1L : 0L;
                    }
                    if ("countEffectivePermission".equals(method.getName())) {
                        return 0L;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        PlatformAdminService platformAdminService =
                new PlatformAdminService(systemRepository, userRoleRepository);
        CurrentUserProvider currentUserProvider = new CurrentUserProvider(platformAdminService);
        ManagementContextService managementContextService =
                new ManagementContextService(currentUserProvider, systemRepository, userRoleRepository);
        service = new SystemCredentialService(systemRepository, managementContextService);
    }

    @AfterEach
    void tearDown() {
        SecurityTestSupport.reset();
    }

    @Test
    void currentReturnsFullCredentialOfContextSystem() {
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode("crm");

        SystemCredentialVO credential = service.current();

        assertThat(credential.systemId()).isEqualTo(CRM_ID);
        assertThat(credential.systemCode()).isEqualTo("crm");
        assertThat(credential.systemName()).isEqualTo("CRM系统");
        assertThat(credential.region()).isEqualTo("region_a");
        assertThat(credential.appKey()).isEqualTo("crm");
        assertThat(credential.appSecret()).isEqualTo("crm-dev-secret");
    }

    @Test
    void currentUsesHeaderContextNotFixedSystem() {
        SecurityTestSupport.authenticate("erp-admin-001");
        SecurityTestSupport.requestWithSystemCode("erp");

        SystemCredentialVO credential = service.current();

        assertThat(credential.systemId()).isEqualTo(ERP_ID);
        assertThat(credential.systemCode()).isEqualTo("erp");
        assertThat(credential.appSecret()).isEqualTo("erp-dev-secret");
    }

    @Test
    void currentRejectsHeaderWithoutEffectiveRole() {
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode("erp");

        assertThatThrownBy(() -> service.current())
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(403));
    }

    @Test
    void currentRejectsMissingSystemCodeHeader() {
        SecurityTestSupport.authenticate("crm-admin-001");
        SecurityTestSupport.requestWithSystemCode(null);

        assertThatThrownBy(() -> service.current())
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> {
                            assertThat(exception.getStatus()).isEqualTo(403);
                            assertThat(exception.getMessage()).contains("X-System-Code");
                        });
    }

    @Test
    void bySystemIdReturnsAnyBusinessSystem() {
        SystemCredentialVO credential = service.bySystemId(ERP_ID);

        assertThat(credential.systemId()).isEqualTo(ERP_ID);
        assertThat(credential.systemCode()).isEqualTo("erp");
        assertThat(credential.appSecret()).isEqualTo("erp-dev-secret");
    }

    @Test
    void bySystemIdRejectsPlatformAndMissingSystem() {
        assertThatThrownBy(() -> service.bySystemId(PLATFORM_ID))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> service.bySystemId(999L))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getStatus()).isEqualTo(404));
    }

    private static SysSystem system(Long id, String code, String name, String region,
                                    String appKey, String appSecret, int isPlatform) {
        SysSystem system = new SysSystem();
        system.setId(id);
        system.setCode(code);
        system.setName(name);
        system.setRegion(region);
        system.setStatus(1);
        system.setIsPlatform(isPlatform);
        system.setAppKey(appKey);
        system.setAppSecret(appSecret);
        return system;
    }
}
