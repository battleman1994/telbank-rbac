package com.telbank.crm.controller;

import com.telbank.rbac.sdk.annotation.RbacPermission;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 示例业务接口：演示「权限码 → method + api_pattern → SDK 拦截」链路。
 *
 * <p>权限中心 seed 数据中：
 * <ul>
 *   <li>CRM：GET /api/order/** → order:list；DELETE /api/order/{id} → order:delete；POST /api/order/export → order:export</li>
 *   <li>ERP：GET /api/order/** → order:list；DELETE /api/order/{id} → order:delete</li>
 * </ul>
 * 未配置权限码的接口（如 /api/health、/api/common/profile）映射未命中 → 默认放行。
 */
@RestController
@RequestMapping("/api")
public class OrderController {

    @RbacPermission(value = "order:list", name = "订单列表")
    @GetMapping("/order/list")
    public Map<String, Object> list() {
        return ok("order-list", "查询订单列表成功");
    }

    @RbacPermission(value = "order:delete", name = "删除订单")
    @DeleteMapping("/order/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        return ok("order-delete", "删除订单成功: " + id);
    }

    @RbacPermission(value = "order:export", name = "导出订单")
    @PostMapping("/order/export")
    public Map<String, Object> export() {
        return ok("order-export", "导出任务已创建");
    }

    private Map<String, Object> ok(String action, String message) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("success", true);
        body.put("action", action);
        body.put("message", message);
        return body;
    }
}
