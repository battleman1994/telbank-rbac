package com.telbank.rbac.security;

/**
 * 当前认证用户上下文。
 *
 * @param externalUserId 外部用户 ID（JWT sub）
 * @param platformAdmin  是否平台超管（命中 rbac.platform-admin-ids）
 */
public record CurrentUser(String externalUserId, boolean platformAdmin) {
}
