package com.telbank.rbac.sdk.client;

/**
 * 权限中心开放接口统一响应信封（契约 §1.2）：{@code {"code":0,"message":"ok","data":<payload>}}。
 *
 * <p>这是普通 Java 业务对象：由 Spring 的 Jackson 消息转换器直接反序列化，
 * 业务/客户端代码只消费 {@link #getData()}，不再手写 JSON 树或字节解码。</p>
 *
 * @param <T> {@code data} 字段的业务类型
 */
final class RbacEnvelope<T> {

    private int code;

    private String message;

    private T data;

    RbacEnvelope() {
    }

    int getCode() {
        return code;
    }

    void setCode(int code) {
        this.code = code;
    }

    String getMessage() {
        return message;
    }

    void setMessage(String message) {
        this.message = message;
    }

    T getData() {
        return data;
    }

    void setData(T data) {
        this.data = data;
    }
}
