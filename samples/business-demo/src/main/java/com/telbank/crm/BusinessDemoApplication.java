package com.telbank.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 验收用示例业务系统入口。
 *
 * <p>CRM 实例：java -jar business-demo.jar --server.port=8081
 * <p>ERP 实例：java -jar business-demo.jar --server.port=8082
 *   --rbac-sdk.system-code=erp --rbac-sdk.app-key=erp --rbac-sdk.app-secret=erp-dev-secret
 */
@SpringBootApplication
public class BusinessDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(BusinessDemoApplication.class, args);
    }
}
