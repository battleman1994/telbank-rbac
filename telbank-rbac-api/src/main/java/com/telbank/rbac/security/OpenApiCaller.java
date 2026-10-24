package com.telbank.rbac.security;

/**
 * 通过 AppKey 验签后得到的开放接口调用方上下文。
 *
 * @param appKey     AppKey
 * @param systemCode AppKey 绑定的系统编码（审批回调为 null）
 * @param approval   是否为审批回调
 */
public record OpenApiCaller(String appKey, String systemCode, boolean approval) {
}
