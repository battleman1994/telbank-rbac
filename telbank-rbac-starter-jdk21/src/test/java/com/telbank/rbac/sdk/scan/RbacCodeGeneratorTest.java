package com.telbank.rbac.sdk.scan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.telbank.rbac.sdk.config.RbacSdkProperties;

/**
 * 候选权限码生成规则（契约 §7.3）。
 */
class RbacCodeGeneratorTest {

    @Test
    void resourceNameStripsControllerSuffixAndConvertsToLowerKebab() {
        assertThat(RbacCodeGenerator.resourceName(OrderController.class)).isEqualTo("order");
        assertThat(RbacCodeGenerator.resourceName(OrderItemController.class)).isEqualTo("order-item");
        assertThat(RbacCodeGenerator.resourceName(URLController.class)).isEqualTo("url");
        assertThat(RbacCodeGenerator.resourceName(User2Controller.class)).isEqualTo("user2");
    }

    @Test
    void actionNameMapsHttpMethods() {
        assertThat(RbacCodeGenerator.actionName("GET")).isEqualTo("list");
        assertThat(RbacCodeGenerator.actionName("POST")).isEqualTo("create");
        assertThat(RbacCodeGenerator.actionName("PUT")).isEqualTo("update");
        assertThat(RbacCodeGenerator.actionName("DELETE")).isEqualTo("delete");
        assertThat(RbacCodeGenerator.actionName("PATCH")).isEqualTo("patch");
        assertThat(RbacCodeGenerator.actionName("get")).isEqualTo("list");
        assertThat(RbacCodeGenerator.actionName("HEAD")).isEqualTo("head");
    }

    @Test
    void generatesCodeWithDefaultPattern() {
        RbacCodeGenerator generator = new RbacCodeGenerator(properties());
        assertThat(generator.generate("crm", OrderController.class, "DELETE"))
                .isEqualTo("crm:order:delete");
        assertThat(generator.generate("crm", OrderItemController.class, "GET"))
                .isEqualTo("crm:order-item:list");
    }

    @Test
    void supportsCustomPattern() {
        RbacSdkProperties properties = properties();
        properties.getCodeGenerator().setPattern("{systemCode}/{resource}#{action}");
        RbacCodeGenerator generator = new RbacCodeGenerator(properties);
        assertThat(generator.generate("erp", OrderController.class, "POST"))
                .isEqualTo("erp/order#create");
    }

    @Test
    void camelToKebabKeepsDigitsAndHandlesAcronyms() {
        assertThat(RbacCodeGenerator.camelToKebab("OrderItem")).isEqualTo("order-item");
        assertThat(RbacCodeGenerator.camelToKebab("URLPath")).isEqualTo("url-path");
        assertThat(RbacCodeGenerator.camelToKebab("Sku2Item")).isEqualTo("sku2-item");
        assertThat(RbacCodeGenerator.camelToKebab("order")).isEqualTo("order");
        assertThat(RbacCodeGenerator.camelToKebab("")).isEmpty();
    }

    private static RbacSdkProperties properties() {
        RbacSdkProperties properties = new RbacSdkProperties();
        properties.setSystemCode("crm");
        return properties;
    }

    static class OrderController {
    }

    static class OrderItemController {
    }

    static class URLController {
    }

    static class User2Controller {
    }
}
