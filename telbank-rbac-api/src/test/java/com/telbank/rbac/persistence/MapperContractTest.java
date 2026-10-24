package com.telbank.rbac.persistence;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.stream.Stream;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.telbank.rbac.domain.entity.BaseEntity;
import com.telbank.rbac.domain.entity.SysPageAction;
import com.telbank.rbac.domain.entity.SysPermission;
import com.telbank.rbac.domain.entity.SysRole;
import com.telbank.rbac.domain.entity.SysRolePermission;
import com.telbank.rbac.domain.entity.SysSdkEndpoint;
import com.telbank.rbac.domain.entity.SysSystemApplication;
import com.telbank.rbac.domain.entity.SysSystem;
import com.telbank.rbac.domain.entity.SysUserRole;
import com.telbank.rbac.domain.mapper.SysPageActionMapper;
import com.telbank.rbac.domain.mapper.SysPermissionMapper;
import com.telbank.rbac.domain.mapper.SysRoleMapper;
import com.telbank.rbac.domain.mapper.SysRolePermissionMapper;
import com.telbank.rbac.domain.mapper.SysSdkEndpointMapper;
import com.telbank.rbac.domain.mapper.SysSystemApplicationMapper;
import com.telbank.rbac.domain.mapper.SysSystemMapper;
import com.telbank.rbac.domain.mapper.SysUserRoleMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 持久层契约（MyBatis-Plus）：实体注解、Mapper 继承关系、分页方法签名与复杂时效 SQL 语义。
 */
class MapperContractTest {

    @Test
    void entitiesUseMyBatisPlusTableAnnotations() {
        assertTableName(SysSystem.class, "sys_system");
        assertTableName(SysRole.class, "sys_role");
        assertTableName(SysPermission.class, "sys_permission");
        assertTableName(SysUserRole.class, "sys_user_role");
        assertTableName(SysRolePermission.class, "sys_role_permission");
        assertTableName(SysPageAction.class, "sys_page_action");
        assertTableName(SysSdkEndpoint.class, "sys_sdk_endpoint");
        assertTableName(SysSystemApplication.class, "sys_system_application");
    }

    @Test
    void baseEntityUsesAutoIncrementIdAndNoJpaAnnotations() throws NoSuchFieldException {
        Field id = BaseEntity.class.getDeclaredField("id");
        TableId tableId = id.getAnnotation(TableId.class);
        assertThat(tableId).isNotNull();
        assertThat(tableId.type()).isEqualTo(IdType.AUTO);
        assertThat(tableId.value()).isEqualTo("id");

        assertNoJakartaPersistence(SysSystem.class);
        assertNoJakartaPersistence(SysRole.class);
        assertNoJakartaPersistence(SysPermission.class);
        assertNoJakartaPersistence(SysUserRole.class);
        assertNoJakartaPersistence(SysRolePermission.class);
        assertNoJakartaPersistence(SysPageAction.class);
        assertNoJakartaPersistence(SysSdkEndpoint.class);
        assertNoJakartaPersistence(SysSystemApplication.class);
    }

    @Test
    void jsonColumnsAreMappedAsStringWithoutJpaJsonTypes() throws NoSuchFieldException {
        Field meta = SysPermission.class.getDeclaredField("meta");
        Field config = SysPageAction.class.getDeclaredField("config");
        assertThat(meta.getType()).isEqualTo(String.class);
        assertThat(config.getType()).isEqualTo(String.class);
        assertThat(annotationNames(meta))
                .noneMatch(name -> name.startsWith("com.fasterxml") || name.startsWith("tools.jackson"));
        assertThat(annotationNames(config))
                .noneMatch(name -> name.startsWith("com.fasterxml") || name.startsWith("tools.jackson"));
    }

    @Test
    void mappersExtendBaseMapper() {
        Stream.of(SysSystemMapper.class, SysRoleMapper.class, SysPermissionMapper.class,
                        SysUserRoleMapper.class, SysRolePermissionMapper.class,
                        SysPageActionMapper.class, SysSdkEndpointMapper.class,
                        SysSystemApplicationMapper.class)
                .forEach(mapper -> assertThat(BaseMapper.class)
                        .as(mapper.getSimpleName() + " extends BaseMapper")
                        .isAssignableFrom(mapper));
    }

    @Test
    void paginatedSearchesUseMyBatisPlusPageAsFirstParameter() throws NoSuchMethodException {
        assertPaginatedSearch(SysSystemMapper.class, "search", String.class);
        assertPaginatedSearch(SysRoleMapper.class, "search", Long.class, String.class);
        assertPaginatedSearch(SysPageActionMapper.class, "search", Long.class, String.class);
        assertPaginatedSearch(SysSdkEndpointMapper.class, "search", Long.class, String.class);
    }

    @Test
    void complexValiditySqlKeepsTimeFilterSemantics() throws NoSuchMethodException {
        String effectiveValidity = selectSql(SysUserRoleMapper.class,
                "findEffectivePermissionValidity", Long.class, String.class);
        assertThat(effectiveValidity)
                .contains("ur.valid_from IS NULL OR ur.valid_from <= NOW()")
                .contains("ur.valid_until IS NULL OR ur.valid_until > NOW()")
                .contains("rp.valid_from IS NULL OR rp.valid_from <= NOW()")
                .contains("rp.valid_until IS NULL OR rp.valid_until > NOW()")
                .contains("ur.status = 1")
                .contains("rp.status = 1")
                .contains("r.status = 1")
                .contains("p.status = 1")
                .contains("UNION ALL")
                .contains("is_public = 1")
                .contains("DATE_FORMAT");

        assertThat(selectSql(SysUserRoleMapper.class, "countEffectiveRoles", Long.class, String.class))
                .contains("ur.status = 1", "r.status = 1", "NOW()");
        assertThat(selectSql(SysUserRoleMapper.class, "countEffectivePermission",
                Long.class, String.class, String.class))
                .contains("p.code = #{code}", "rp.status = 1", "NOW()");
        assertThat(selectSql(SysUserRoleMapper.class, "findAccessibleSystemIds", String.class))
                .contains("DISTINCT ur.system_id", "NOW()");

        assertThat(selectSql(SysRolePermissionMapper.class, "findGrantedCodesByRoleId", Long.class))
                .contains("rp.status = 1", "p.status = 1");

        assertThat(updateSql(SysUserRoleMapper.class, "revokeExpired"))
                .contains("status = 0", "valid_until <= NOW()", "auto_revoke = 1");
        assertThat(updateSql(SysRolePermissionMapper.class, "revokeExpired"))
                .contains("status = 0", "valid_until <= NOW()", "auto_revoke = 1");
    }

    @Test
    void systemApplicationSearchFiltersByStatus() throws NoSuchMethodException {
        String applicationSearch = selectSql(SysSystemApplicationMapper.class, "search",
                Page.class, Integer.class);
        assertThat(applicationSearch)
                .contains("sys_system_application", "status = #{status}", "ORDER BY id DESC");
    }

    @Test
    void searchSqlKeepsSystemIsolationAndKeywordFilters() throws NoSuchMethodException {
        String systemSearch = selectSql(SysSystemMapper.class, "search", Page.class, String.class);
        assertThat(systemSearch).contains("is_platform", "LOWER(code)", "LOWER(name)", "ORDER BY sort");

        String roleSearch = selectSql(SysRoleMapper.class, "search", Page.class, Long.class, String.class);
        assertThat(roleSearch).contains("system_id = #{systemId}", "LOWER(code)", "LOWER(name)");

        String pageActionSearch = selectSql(SysPageActionMapper.class, "search",
                Page.class, Long.class, String.class);
        assertThat(pageActionSearch).contains("system_id = #{systemId}", "page_code");

        String endpointSearch = selectSql(SysSdkEndpointMapper.class, "search",
                Page.class, Long.class, String.class);
        assertThat(endpointSearch).contains("system_id = #{systemId}", "candidate_code");
    }

    // ------------------------------------------------------------------

    private static void assertTableName(Class<?> entity, String expected) {
        TableName tableName = entity.getAnnotation(TableName.class);
        assertThat(tableName).as(entity.getSimpleName() + " @TableName").isNotNull();
        assertThat(tableName.value()).isEqualTo(expected);
    }

    private static void assertNoJakartaPersistence(Class<?> entity) {
        assertThat(annotationNames(entity))
                .noneMatch(name -> name.startsWith("jakarta.persistence")
                        || name.startsWith("org.hibernate"));
        for (Field field : entity.getDeclaredFields()) {
            assertThat(annotationNames(field))
                    .as(entity.getSimpleName() + "." + field.getName())
                    .noneMatch(name -> name.startsWith("jakarta.persistence")
                            || name.startsWith("org.hibernate"));
        }
    }

    private static List<String> annotationNames(java.lang.reflect.AnnotatedElement element) {
        return Stream.of(element.getAnnotations())
                .map(Annotation::annotationType)
                .map(Class::getName)
                .toList();
    }

    private static void assertPaginatedSearch(Class<?> mapper, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = mapper.getMethod(methodName, prependPage(parameterTypes));
        assertThat(Page.class).isAssignableFrom(method.getParameterTypes()[0]);
        assertThat(method.getReturnType()).isEqualTo(List.class);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as(mapper.getSimpleName() + "." + methodName + " @Select").isNotNull();
        Parameter[] parameters = method.getParameters();
        assertThat(parameters[1].isAnnotationPresent(org.apache.ibatis.annotations.Param.class)
                || parameters.length > 1).isTrue();
    }

    private static Class<?>[] prependPage(Class<?>[] parameterTypes) {
        Class<?>[] result = new Class<?>[parameterTypes.length + 1];
        result[0] = Page.class;
        System.arraycopy(parameterTypes, 0, result, 1, parameterTypes.length);
        return result;
    }

    private static String selectSql(Class<?> mapper, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = mapper.getMethod(methodName, parameterTypes);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as(mapper.getSimpleName() + "." + methodName + " @Select").isNotNull();
        return String.join(" ", select.value());
    }

    private static String updateSql(Class<?> mapper, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = mapper.getMethod(methodName, parameterTypes);
        Update update = method.getAnnotation(Update.class);
        assertThat(update).as(mapper.getSimpleName() + "." + methodName + " @Update").isNotNull();
        return String.join(" ", update.value());
    }
}
