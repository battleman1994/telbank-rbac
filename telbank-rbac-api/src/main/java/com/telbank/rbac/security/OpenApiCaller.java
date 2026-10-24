package com.telbank.rbac.security;

/**
 * 通过 AppKey 验签后得到的开放接口调用方上下文。
 *
 * @param appKey     AppKey
 * @param systemCode AppKey 绑定的系统编码（审批/系统接入上报为 null）
 * @param approval   是否为审批回调
 * @param onboarding 是否为 SDK 系统接入上报
 */
public record OpenApiCaller(String appKey, String systemCode, boolean approval, boolean onboarding) {

    /** 兼容旧构造器（非 onboarding）。 */
    public OpenApiCaller(String appKey, String systemCode, boolean approval) {
        this(appKey, systemCode, approval, false);
    }
}
