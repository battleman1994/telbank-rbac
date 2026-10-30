package com.telbank.rbac.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;

/**
 * MyBatis-Plus 配置：Mapper 扫描 + 分页插件（方言按 JDBC 连接自动识别：MySQL / H2 standalone）。
 */
@Configuration
@MapperScan("com.telbank.rbac.domain.mapper")
public class MybatisPlusConfig {

    /** 单页最大条数，防止恶意超大分页。 */
    private static final long MAX_PAGE_SIZE = 500L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 不固定 DbType：MyBatis-Plus 按连接自动识别方言，兼容 MySQL 与 H2（MODE=MySQL）
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor();
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
