package com.wxx.aidocumentagent.common.web;

import java.util.List;

import com.wxx.aidocumentagent.common.api.ApiResponse;
import com.wxx.aidocumentagent.common.api.BusinessException;
import com.wxx.aidocumentagent.common.api.CommonErrorCode;
import com.wxx.aidocumentagent.common.api.ErrorCode;
import com.wxx.aidocumentagent.common.api.FieldValidationError;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 将可预期的应用异常和输入异常转换为统一 REST 响应包络。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        return response(errorCode, exception.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<List<FieldValidationError>>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception) {
        return validationResponse(toFieldErrors(exception.getBindingResult().getFieldErrors()));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<List<FieldValidationError>>> handleBindException(BindException exception) {
        return validationResponse(toFieldErrors(exception.getBindingResult().getFieldErrors()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<List<FieldValidationError>>> handleHandlerMethodValidation(
            HandlerMethodValidationException exception) {
        List<FieldValidationError> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldValidationError(result.getMethodParameter().getParameterName(),
                                defaultMessage(error))))
                .toList();
        return validationResponse(errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<List<FieldValidationError>>> handleConstraintViolation(
            ConstraintViolationException exception) {
        List<FieldValidationError> errors = exception.getConstraintViolations().stream()
                .map(violation -> new FieldValidationError(lastPathSegment(violation.getPropertyPath().toString()),
                        violation.getMessage()))
                .toList();
        return validationResponse(errors);
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception exception) {
        return response(CommonErrorCode.BAD_REQUEST, CommonErrorCode.BAD_REQUEST.defaultMessage(), null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException exception) {
        return response(CommonErrorCode.FILE_TOO_LARGE, CommonErrorCode.FILE_TOO_LARGE.defaultMessage(), null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException exception) {
        return response(CommonErrorCode.RESOURCE_NOT_FOUND, CommonErrorCode.RESOURCE_NOT_FOUND.defaultMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        LOGGER.error("请求处理发生未捕获异常", exception);
        return response(CommonErrorCode.INTERNAL_ERROR, CommonErrorCode.INTERNAL_ERROR.defaultMessage(), null);
    }

    private ResponseEntity<ApiResponse<List<FieldValidationError>>> validationResponse(List<FieldValidationError> errors) {
        return response(CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.VALIDATION_ERROR.defaultMessage(), errors);
    }

    private <T> ResponseEntity<ApiResponse<T>> response(ErrorCode errorCode, String message, T data) {
        ApiResponse<T> body = ApiResponse.failure(errorCode, message, data, TraceIdFilter.currentTraceId());
        return ResponseEntity.status(errorCode.status()).body(body);
    }

    private List<FieldValidationError> toFieldErrors(List<FieldError> fieldErrors) {
        return fieldErrors.stream()
                .map(error -> new FieldValidationError(error.getField(), defaultMessage(error)))
                .toList();
    }

    private String defaultMessage(MessageSourceResolvable error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage()
                : CommonErrorCode.VALIDATION_ERROR.defaultMessage();
    }

    private String lastPathSegment(String path) {
        int separator = Math.max(path.lastIndexOf('.'), path.lastIndexOf(']'));
        return separator >= 0 ? path.substring(separator + 1) : path;
    }
}
