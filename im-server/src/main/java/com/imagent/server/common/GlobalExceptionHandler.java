package com.imagent.server.common;

import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局 HTTP 异常拦截，统一封装为 Result 返回
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e) {
        return Result.fail(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public Result<Void> handleBadFormat(Exception e) {
        log.warn("请求参数格式错误: {}", e.getMessage());
        return Result.fail(ErrorCode.BAD_REQUEST.getCode(), "请求参数格式不合法（请检查用户ID等字段格式）");
    }

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(ErrorCode.BAD_REQUEST.getCode(), detail);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpectedException(Exception e) {
        log.error("未捕获系统异常", e);
        return Result.fail(ErrorCode.INTERNAL_ERROR);
    }
}
