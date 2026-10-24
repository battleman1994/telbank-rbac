package com.telbank.crm.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 未配置权限码的示例接口：用于验证「映射表未命中 → 直接放行，不查权限集、不回源」。
 */
@RestController
@RequestMapping("/api")
public class CommonController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("status", "UP");
        body.put("service", "business-demo");
        return body;
    }

    @GetMapping("/common/profile")
    public Map<String, Object> profile() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("externalUserId", "anonymous-or-principal");
        body.put("message", "公开接口，无需权限码");
        return body;
    }
}
