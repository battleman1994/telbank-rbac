package com.telbank.rbac.common;

/**
 * 统一响应信封（契约 §1.2）。
 *
 * <pre>
 * 成功：{"code":0,"message":"ok","data":{...}}
 * 失败：{"code":&lt;httpStatus&gt;,"message":"...","data":null}（HTTP 状态码非 2xx）
 * </pre>
 *
 * @param code    业务码，0 表示成功，失败时等于 HTTP 状态码
 * @param message 可读信息
 * @param data    业务数据
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static final int SUCCESS_CODE = 0;
    public static final String SUCCESS_MESSAGE = "ok";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
