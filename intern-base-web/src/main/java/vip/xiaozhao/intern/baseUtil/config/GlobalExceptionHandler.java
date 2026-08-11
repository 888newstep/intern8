package vip.xiaozhao.intern.baseUtil.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import vip.xiaozhao.intern.baseUtil.intf.dto.ResponseDO;
import vip.xiaozhao.intern.baseUtil.intf.exception.BusinessException;
import vip.xiaozhao.intern.baseUtil.intf.exception.ErrorCode;
import vip.xiaozhao.intern.baseUtil.service.CacheSingleFlightTimeoutException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthenticationException.class)
    public ResponseDO handleAuthenticationException(AuthenticationException e) {
        return ResponseDO.fail(401, "Authentication failed: not logged in or token expired");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseDO handleAccessDeniedException(AccessDeniedException e) {
        return ResponseDO.fail(403, "Access denied");
    }

    @ExceptionHandler(TransactionTimedOutException.class)
    public ResponseDO handleTransactionTimedOut(TransactionTimedOutException e) {
        logger.warn("Transaction timed out: {}", e.getMessage());
        return ResponseDO.fail(503, "System busy, please retry");
    }

    @ExceptionHandler(CacheSingleFlightTimeoutException.class)
    public ResponseDO handleCacheSingleFlightTimeout(CacheSingleFlightTimeoutException e) {
        logger.warn("Cache single-flight wait timed out: {}", e.getMessage());
        return ResponseDO.fail(503, "System busy, please retry");
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseDO handleBusinessException(BusinessException e) {
        return ResponseDO.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseDO handleValidationException(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        return ResponseDO.fail(ErrorCode.BAD_REQUEST.getCode(), "Validation failed: " + errors.toString());
    }

    @ExceptionHandler(Exception.class)
    public ResponseDO handleException(Exception e) {
        logger.error("Unexpected exception occurred", e);
        return ResponseDO.fail(ErrorCode.INTERNAL_ERROR.getCode(), "Internal server error");
    }
}
