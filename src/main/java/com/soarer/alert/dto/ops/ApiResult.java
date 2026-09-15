package com.soarer.alert.dto.ops;

/**
 * ApiResult 数据传输对象。
 */
public record ApiResult<T>(int code, String message, T data) {

    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(200, "success", data);
    }

    public static <T> ApiResult<T> error(int code, String message) {
        return new ApiResult<>(code, message, null);
    }
}
