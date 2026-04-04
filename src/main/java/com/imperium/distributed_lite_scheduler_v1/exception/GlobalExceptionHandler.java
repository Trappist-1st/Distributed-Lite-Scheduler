package com.imperium.distributed_lite_scheduler_v1.exception;

import com.imperium.distributed_lite_scheduler_v1.utils.Result;
import com.imperium.distributed_lite_scheduler_v1.utils.ResultCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将参数校验等异常转为统一 {@link Result}，避免直接暴露框架默认 HTML/空 body。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fe = ex.getBindingResult().getFieldError();
        String msg = fe != null ? fe.getDefaultMessage() : ResultCode.BAD_REQUEST.getMessage();
        return Result.failure(ResultCode.BAD_REQUEST, msg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleBodyMissing(HttpMessageNotReadableException ex) {
        return Result.failure(ResultCode.BAD_REQUEST, "请求体格式错误或不能为空");
    }
}
