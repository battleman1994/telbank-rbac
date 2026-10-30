package com.telbank.rbac;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 权限中心后端启动类。
 *
 * <p>权限中心只做授权（权限码 / 角色 / 授权关系 / 时效 / 接口映射），不做认证：
 * 用户 JWT 由外部认证系统签发，本服务作为 OAuth2 Resource Server 只校验。</p>
 *
 * <p>本服务全链路使用 UTC：数据库 {@code DATETIME} 按 UTC 存储，JSON 时间为 ISO-8601 UTC 字符串
 * （契约 §1.4）。启动时显式把 JVM 默认时区固定为 UTC，避免宿主机时区（如 Asia/Shanghai）在
 * JDBC {@code Timestamp}/{@code LocalDateTime} 转换时造成 ±N 小时偏移。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RbacApiApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
        SpringApplication.run(RbacApiApplication.class, prepareArgs(args));
    }

    /**
     * 支持 {@code --standalone} 启动参数：H2 内存数据库 + 本地缓存（无 MySQL / 无 Redis）。
     *
     * <p>等价于 {@code --spring.profiles.active=standalone}；仍可继续传其它 Spring 参数
     * （如 {@code --server.port=18080}）。</p>
     */
    static String[] prepareArgs(String[] args) {
        if (args == null || args.length == 0) {
            return args;
        }
        List<String> effective = new ArrayList<String>(args.length + 1);
        boolean standalone = false;
        for (String arg : args) {
            if ("--standalone".equals(arg)) {
                standalone = true;
            } else {
                effective.add(arg);
            }
        }
        if (standalone) {
            effective.add("--spring.profiles.active=standalone");
        }
        return effective.toArray(new String[0]);
    }
}
