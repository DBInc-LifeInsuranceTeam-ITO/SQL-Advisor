package dbinc.sqladvisor.util;

import dbinc.sqladvisor.common.exception.InvalidRequestDataException;
import org.springframework.util.StringUtils;

/**
 * 공통 검증 유틸리티 클래스
 */
public final class ValidationUtils {
    
    private ValidationUtils() {
        // 유틸리티 클래스는 인스턴스화 방지
    }
    
    /**
     * 문자열이 비어있지 않은지 검증
     * @param value 검증할 값
     * @param fieldName 필드명
     * @throws InvalidRequestDataException 값이 비어있을 경우
     */
    public static void requireNonEmpty(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new InvalidRequestDataException(fieldName, fieldName + "은(는) 필수입니다.");
        }
    }
    
    /**
     * 객체가 null이 아닌지 검증
     * @param value 검증할 값
     * @param fieldName 필드명
     * @throws InvalidRequestDataException 값이 null일 경우
     */
    public static void requireNonNull(Object value, String fieldName) {
        if (value == null) {
            throw new InvalidRequestDataException(fieldName, fieldName + "은(는) 필수입니다.");
        }
    }
    
    /**
     * ID가 null이 아닌지 검증 (IllegalArgumentException 던짐)
     * @param id 검증할 ID
     * @param message 에러 메시지
     * @throws IllegalArgumentException ID가 null일 경우
     */
    public static void requireValidId(Long id, String message) {
        if (id == null) {
            throw new IllegalArgumentException(message);
        }
    }
    
    /**
     * 문자열이 비어있지 않은지 검증 (IllegalArgumentException 던짐)
     * @param value 검증할 값
     * @param message 에러 메시지
     * @throws IllegalArgumentException 값이 비어있을 경우
     */
    public static void requireNonEmptyString(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}