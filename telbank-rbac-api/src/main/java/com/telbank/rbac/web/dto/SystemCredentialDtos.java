package com.telbank.rbac.web.dto;

/**
 * 系统接入凭据 DTO（A18）：返回完整 appKey/appSecret，仅授权管理员可见。
 */
public final class SystemCredentialDtos {

    private SystemCredentialDtos() {
    }

    /**
     * 系统接入凭据（完整值）。
     *
     * @param systemId   业务系统 id
     * @param systemCode 系统编码
     * @param systemName 系统名称
     * @param region     所属区域
     * @param appKey     开放接口 AppKey
     * @param appSecret  开放接口 AppSecret（完整值，仅授权用户可见）
     */
    public record SystemCredentialVO(Long systemId,
                                     String systemCode,
                                     String systemName,
                                     String region,
                                     String appKey,
                                     String appSecret) {
    }
}
