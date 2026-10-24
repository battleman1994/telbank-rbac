package com.telbank.rbac.sdk.client;

/**
 * SDK 调用权限中心开放接口失败时抛出的非受检异常。
 *
 * <p>SDK 内部所有回源/上报失败都以该异常（或其包装）表达，由调用方决定降级策略：
 * 过滤器放行、缓存使用旧值、启动上报只告警等。</p>
 */
public class RbacRemoteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** HTTP 状态码；0 表示未拿到 HTTP 响应（网络/解析/本地错误）。 */
    private final int statusCode;

    public RbacRemoteException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public RbacRemoteException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public RbacRemoteException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public RbacRemoteException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
