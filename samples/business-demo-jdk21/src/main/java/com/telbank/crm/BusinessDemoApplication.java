package com.telbank.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 验收用示例业务系统入口（JDK 21 + Spring Boot 4.x + telbank-rbac-starter-jdk21）。
 *
 * <p>CRM 实例：java -jar business-demo-jdk21.jar --server.port=8091
 * <p>ERP 实例：java -jar business-demo-jdk21.jar --server.port=8092
 *   --rbac-sdk.system-code=erp --rbac-sdk.app-key=erp --rbac-sdk.app-secret=erp-dev-secret
 */
@SpringBootApplication
public class BusinessDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(BusinessDemoApplication.class, args);
    }
}
