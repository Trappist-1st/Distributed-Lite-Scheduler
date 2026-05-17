package com.imperium.distributed_lite_scheduler_v1.utils;

import lombok.Getter;
import lombok.ToString;

/**
 * 统一 API 响应封装。建议 Controller 层统一返回 {@code Result<T>}。
 *
 * @param <T> 业务数据类型，无载荷时使用 {@link Void} 或 {@link #success()}。
 */
@Getter
@ToString
public final class Result<T> {

    //采用静态工厂方法，禁止外部直接构造，确保成功/失败语义清晰且不可变。
    private final boolean success;
    private final int code;
    private final String message;
    private final T data;
    private final long timestamp;

    private Result(boolean success, int code, String message, T data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    // ---------- 成功 ----------

    public static <T> Result<T> success() {
        return new Result<>(true, ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(true, ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(true, ResultCode.SUCCESS.getCode(), message, data);
    }

    // ---------- 失败（枚举） ----------

    public static <T> Result<T> failure(ResultCode resultCode) {
        return new Result<>(false, resultCode.getCode(), resultCode.getMessage(), null);
    }

    public static <T> Result<T> failure(ResultCode resultCode, String message) {
        return new Result<>(false, resultCode.getCode(), message, null);
    }

    public static <T> Result<T> failure(ResultCode resultCode, String message, T data) {
        return new Result<>(false, resultCode.getCode(), message, data);
    }

    // ---------- 失败（自定义码，用于业务码段） ----------

    public static <T> Result<T> failure(int code, String message) {
        return new Result<>(false, code, message, null);
    }

    public static <T> Result<T> failure(int code, String message, T data) {
        return new Result<>(false, code, message, data);
    }

    /**
     * 从当前结果映射为新载荷类型（成功时替换 data，失败时保留 code/message）。
     */
    public <R> Result<R> map(java.util.function.Function<? super T, ? extends R> mapper) {
        if (!success) {
            return Result.failure(code, message);
        }
        return Result.success(message, mapper.apply(data));
    }
}
