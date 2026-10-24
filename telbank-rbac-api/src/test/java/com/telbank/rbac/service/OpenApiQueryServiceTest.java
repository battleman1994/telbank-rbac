package com.telbank.rbac.service;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.telbank.rbac.config.RbacProperties;
import com.telbank.rbac.domain.entity.SysPageAction;
import com.telbank.rbac.domain.entity.SysPermission;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.mapper.SysPageActionMapper;
import com.telbank.rbac.domain.mapper.SysPermissionMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.service.cache.LocalCacheManager;
import com.telbank.rbac.testutil.TestProxies;
import com.telbank.rbac.web.dto.OpenApiDtos.ConnectionResponse;
import com.telbank.rbac.web.dto.OpenApiDtos.MappingsResponse;
import com.telbank.rbac.web.dto.OpenApiDtos.PageActionsResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 映射表 / 页面操作 / 区域 Redis 连接下发（契约 §4.2 / §6.8）。
 */
class OpenApiQueryServiceTest {

    private OpenApiQueryService service;

    @BeforeEach
    void setUp() {
        SysSystem crm = new SysSystem();
        crm.setId(1L);
        crm.setCode("crm");
        crm.setName("CRM系统");
        crm.setRegion("region_a");
        crm.setStatus(1);

        List<SysPermission> permissions = List.of(
                permission(1L, "order:list", "GET", "/api/order/**", 1),
                permission(2L, "order:delete", "DELETE", "/api/order/{id}", 1),
                permission(3L, "order:menu", null, null, 1),
                permission(4L, "order:export", "POST", "/api/order/export", 0),
                permission(5L, "order:approve", "POST", " ", 1));

        List<SysPageAction> actions = List.of(
                pageAction(1L, "order:list", "order:export", "导出", "callApi", "{}", 20, 1),
                pageAction(2L, "order:list", "order:delete", "删除", "confirmThenCallApi", "{}", 50, 1),
                pageAction(3L, "purchase:list", "purchase:create", "新建采购单", "openDialog", "{}", 10, 1),
                pageAction(4L, "order:list", "order:disabled", "已停用", "download", "{}", 60, 0));

        SysSystemMapper systemRepository = TestProxies.proxy(SysSystemMapper.class,
                (proxy, method, args) -> {
                    if ("findByCode".equals(method.getName())) {
                        return "crm".equals(args[0]) ? java.util.Optional.of(crm) : java.util.Optional.empty();
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysPermissionMapper permissionRepository = TestProxies.proxy(SysPermissionMapper.class,
                (proxy, method, args) -> {
                    if ("findAllBySystemIdOrderBySortAscIdAsc".equals(method.getName())) {
                        return permissions;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });
        SysPageActionMapper pageActionRepository = TestProxies.proxy(SysPageActionMapper.class,
                (proxy, method, args) -> {
                    if ("findBySystemIdAndStatusOrderBySortAscIdAsc".equals(method.getName())) {
                        return actions;
                    }
                    return TestProxies.defaultValue(method.getReturnType());
                });

        RbacProperties properties = new RbacProperties();
        RbacProperties.Region region = new RbacProperties.Region();
        region.setHost("127.0.0.1");
        region.setPort(6379);
        region.setPassword("secret");
        region.setChannelPrefix("rbac:change:region_a:");
        properties.getRegions().put("region_a", region);

        service = new OpenApiQueryService(systemRepository, permissionRepository,
                pageActionRepository, new LocalCacheManager(Duration.ofMinutes(5), 100), properties);
    }

    @Test
    void mappingsOnlyContainEnabledPermissionsWithMethodAndPattern() {
        MappingsResponse response = service.mappings("crm");

        assertThat(response.systemCode()).isEqualTo("crm");
        assertThat(response.mappings()).hasSize(2);
        assertThat(response.mappings().get(0).method()).isEqualTo("GET");
        assertThat(response.mappings().get(0).pattern()).isEqualTo("/api/order/**");
        assertThat(response.mappings().get(0).code()).isEqualTo("order:list");
        assertThat(response.mappings().get(1).method()).isEqualTo("DELETE");
        // Spring 模板 {id} 原样下发
        assertThat(response.mappings().get(1).pattern()).isEqualTo("/api/order/{id}");
        assertThat(response.mappings().get(1).code()).isEqualTo("order:delete");
    }

    @Test
    void pageActionsFilterByPageCodeAndStatus() {
        PageActionsResponse all = service.pageActions("crm", null);
        assertThat(all.pageActions()).hasSize(3);

        PageActionsResponse orderList = service.pageActions("crm", "order:list");
        assertThat(orderList.pageActions()).hasSize(2)
                .extracting(item -> item.code())
                .containsExactly("order:export", "order:delete");

        PageActionsResponse blank = service.pageActions("crm", " ");
        assertThat(blank.pageActions()).hasSize(3);
    }

    @Test
    void connectionReturnsConfiguredRegionInfo() {
        ConnectionResponse response = service.connection("crm");

        assertThat(response.channel()).isEqualTo("redis");
        assertThat(response.region()).isEqualTo("region_a");
        assertThat(response.host()).isEqualTo("127.0.0.1");
        assertThat(response.port()).isEqualTo(6379);
        assertThat(response.password()).isEqualTo("secret");
        assertThat(response.channelPrefix()).isEqualTo("rbac:change:region_a:");
    }

    private static SysPermission permission(Long id, String code, String method, String pattern, int status) {
        SysPermission permission = new SysPermission();
        permission.setId(id);
        permission.setSystemId(1L);
        permission.setCode(code);
        permission.setName(code);
        permission.setMethod(method);
        permission.setApiPattern(pattern);
        permission.setStatus(status);
        permission.setSort(id.intValue());
        return permission;
    }

    private static SysPageAction pageAction(Long id, String pageCode, String code, String text,
                                            String action, String config, int sort, int status) {
        SysPageAction pageAction = new SysPageAction();
        pageAction.setId(id);
        pageAction.setSystemId(1L);
        pageAction.setPageCode(pageCode);
        pageAction.setCode(code);
        pageAction.setText(text);
        pageAction.setType("default");
        pageAction.setAction(action);
        pageAction.setConfig(config);
        pageAction.setSort(sort);
        pageAction.setStatus(status);
        return pageAction;
    }
}
