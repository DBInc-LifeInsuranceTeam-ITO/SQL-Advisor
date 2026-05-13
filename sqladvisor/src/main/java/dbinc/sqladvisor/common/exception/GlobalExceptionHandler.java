package dbinc.sqladvisor.common.exception;

import dbinc.sqladvisor.common.enums.ErrorCode;
import dbinc.sqladvisor.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AssetNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleAssetNotFoundException(AssetNotFoundException e) {
        log.warn("Certificate/License not found: {}", e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.ASSET_NOT_FOUND, e.getMessage());
        return ResponseEntity.status(ErrorCode.ASSET_NOT_FOUND.getHttpStatus()).body(response);
    }

    @ExceptionHandler(AssetAlreadyExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleAssetAlreadyExistsException(AssetAlreadyExistsException e) {
        log.warn("Certificate/License already exists: {}", e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.ASSET_ALREADY_EXISTS, e.getMessage());
        return ResponseEntity.status(ErrorCode.ASSET_ALREADY_EXISTS.getHttpStatus()).body(response);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorizedException(UnauthorizedException e) {
        log.warn("Unauthorized access attempt: {}", e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.FORBIDDEN, e.getMessage());
        return ResponseEntity.status(ErrorCode.FORBIDDEN.getHttpStatus()).body(response);
    }

    @ExceptionHandler(InvalidSearchConditionException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidSearchConditionException(InvalidSearchConditionException e) {
        log.warn("Invalid search condition: {}", e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.INVALID_SEARCH_CONDITION, e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_SEARCH_CONDITION.getHttpStatus()).body(response);
    }
    
    @ExceptionHandler(ExcelGenerationException.class)
    public ResponseEntity<ApiResponse<Void>> handleExcelGenerationException(ExcelGenerationException e) {
        log.error("Excel generation failed: {}", e.getMessage(), e);
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR, e.getMessage());
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus()).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String errorMessage = e.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
        
        log.warn("Validation error: {}", errorMessage);
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.VALIDATION_ERROR, "입력값 검증 실패: " + errorMessage);
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getHttpStatus()).body(response);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException e) {
        String errorMessage = e.getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
        
        log.warn("Bind error: {}", errorMessage);
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.BIND_ERROR, "입력값 바인딩 실패: " + errorMessage);
        return ResponseEntity.status(ErrorCode.BIND_ERROR.getHttpStatus()).body(response);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(ConstraintViolationException e) {
        String errorMessage = e.getConstraintViolations().stream()
            .map(violation -> violation.getMessage())
            .collect(Collectors.joining(", "));
        
        log.warn("Constraint violation: {}", errorMessage);
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.CONSTRAINT_VIOLATION, "제약 조건 위반: " + errorMessage);
        return ResponseEntity.status(ErrorCode.CONSTRAINT_VIOLATION.getHttpStatus()).body(response);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        log.error("Data integrity violation", e);
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.DATA_INTEGRITY_VIOLATION);
        return ResponseEntity.status(ErrorCode.DATA_INTEGRITY_VIOLATION.getHttpStatus()).body(response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("HTTP message not readable: {}", e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.INVALID_JSON_FORMAT);
        return ResponseEntity.status(ErrorCode.INVALID_JSON_FORMAT.getHttpStatus()).body(response);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.warn("Method argument type mismatch: {}", e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.INVALID_PARAMETER_TYPE, 
            String.format("파라미터 타입이 올바르지 않습니다: %s", e.getName()));
        return ResponseEntity.status(ErrorCode.INVALID_PARAMETER_TYPE.getHttpStatus()).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.INVALID_ARGUMENT, "잘못된 인수: " + e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_ARGUMENT.getHttpStatus()).body(response);
    }
    
    @ExceptionHandler(InvalidRequestDataException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequestDataException(InvalidRequestDataException e) {
        log.warn("Invalid request data - field: {}, message: {}", e.getField(), e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.VALIDATION_ERROR, e.getMessage());
        return ResponseEntity.status(ErrorCode.VALIDATION_ERROR.getHttpStatus()).body(response);
    }
    
    @ExceptionHandler(AssetDeletionRestrictedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAssetDeletionRestrictedException(AssetDeletionRestrictedException e) {
        log.warn("Asset deletion restricted: {}", e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.FORBIDDEN, e.getMessage());
        return ResponseEntity.status(ErrorCode.FORBIDDEN.getHttpStatus()).body(response);
    }
    
    @ExceptionHandler(EsbSystemException.class)
    public ResponseEntity<ApiResponse<Void>> handleEsbSystemException(EsbSystemException e) {
        log.error("ESB system error: {}", e.getMessage(), e);
        ApiResponse<Void> response = ApiResponse.error(e.getErrorCode(), e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(response);
    }
    
    @ExceptionHandler(NotificationException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotificationException(NotificationException e) {
        log.error("Notification error: {}", e.getMessage(), e);
        ApiResponse<Void> response = ApiResponse.error(e.getErrorCode(), e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getHttpStatus()).body(response);
    }
    
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException e) {
        log.debug("No static resource found: {}", e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.ASSET_NOT_FOUND, "요청한 리소스를 찾을 수 없습니다.");
        return ResponseEntity.status(ErrorCode.ASSET_NOT_FOUND.getHttpStatus()).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception e) {
        log.error("Unexpected error occurred", e);
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR);
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getHttpStatus()).body(response);
    }
    
}
