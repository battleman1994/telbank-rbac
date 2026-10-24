package com.telbank.rbac.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 时间转换工具：数据库统一存 UTC {@link LocalDateTime}，JSON 统一输出 ISO-8601 UTC {@link Instant}
 * （契约 §1.4）。
 */
public final class TimeUtils {

    private TimeUtils() {
    }

    public static Instant toInstant(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
    }

    public static LocalDateTime toUtcLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
