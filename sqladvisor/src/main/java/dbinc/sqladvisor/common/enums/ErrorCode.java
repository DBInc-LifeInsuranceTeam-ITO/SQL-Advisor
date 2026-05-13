package dbinc.sqladvisor.common.enums;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    
    // 4xx Client Errors
    BAD_REQUEST("E4000", "잘못된 요청입니다.", HttpStatus.BAD_REQUEST),
    VALIDATION_ERROR("E4001", "입력값 검증에 실패했습니다.", HttpStatus.BAD_REQUEST),
    BIND_ERROR("E4002", "입력값 바인딩에 실패했습니다.", HttpStatus.BAD_REQUEST),
    CONSTRAINT_VIOLATION("E4003", "제약 조건을 위반했습니다.", HttpStatus.BAD_REQUEST),
    INVALID_JSON_FORMAT("E4004", "잘못된 JSON 형식입니다.", HttpStatus.BAD_REQUEST),
    INVALID_PARAMETER_TYPE("E4005", "파라미터 타입이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    INVALID_ARGUMENT("E4006", "잘못된 인수입니다.", HttpStatus.BAD_REQUEST),
    
    UNAUTHORIZED("E4010", "인증이 필요합니다.", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("E4030", "접근 권한이 없습니다.", HttpStatus.FORBIDDEN),
    
    // Business Logic Errors
    RESOURCE_NOT_FOUND("E4040", "요청한 리소스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    ASSET_NOT_FOUND("E4041", "인증서/라이선스를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    
    RESOURCE_CONFLICT("E4090", "리소스 충돌이 발생했습니다.", HttpStatus.CONFLICT),
    ASSET_ALREADY_EXISTS("E4091", "이미 존재하는 인증서/라이선스입니다.", HttpStatus.CONFLICT),
    DATA_INTEGRITY_VIOLATION("E4092", "데이터 무결성 위반이 발생했습니다.", HttpStatus.CONFLICT),
    
    // Search & Validation Errors
    INVALID_SEARCH_CONDITION("E4221", "잘못된 검색 조건입니다.", HttpStatus.UNPROCESSABLE_ENTITY),
    INVALID_DATE_RANGE("E4222", "잘못된 날짜 범위입니다.", HttpStatus.UNPROCESSABLE_ENTITY),
    REQUIRED_FIELD_MISSING("E4223", "필수 필드가 누락되었습니다.", HttpStatus.UNPROCESSABLE_ENTITY),
    
    // 5xx Server Errors
    INTERNAL_SERVER_ERROR("E5000", "서버 내부 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    DATABASE_ERROR("E5001", "데이터베이스 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    EXTERNAL_API_ERROR("E5002", "외부 API 호출 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    
    // Service Specific Errors
    CERTIFICATE_PROCESSING_ERROR("E5100", "인증서 처리 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    EXPIRY_CALCULATION_ERROR("E5101", "만료일 계산 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    
    // ESB Service Errors
    ESB_SYSTEM_ERROR("E5003", "ESB 시스템 연동 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    ESB_TIMEOUT("E5004", "ESB 시스템 응답 시간이 초과되었습니다.", HttpStatus.SERVICE_UNAVAILABLE),
    
    // Notification Service Errors
    NOTIFICATION_SEND_ERROR("E5200", "알림 발송 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    EMAIL_SEND_ERROR("E5201", "이메일 발송 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    SMS_SEND_ERROR("E5202", "SMS 발송 중 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    NOTIFICATION_CONFIG_ERROR("E5203", "알림 설정 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);
    
    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
    
    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
    
    /**
     * 추가 컨텍스트 정보를 포함한 메시지 생성
     */
    public String getFormattedMessage(String... args) {
        if (args.length == 0) {
            return this.message;
        }
        try {
            return String.format(this.message + " %s", String.join(" ", args));
        } catch (Exception e) {
            return this.message;
        }
    }
    
    /**
     * 추가 컨텍스트 정보를 포함한 메시지 생성
     */
    public String getMessageWithContext(String context) {
        return String.format("%s: %s", this.message, context);
    }
}
