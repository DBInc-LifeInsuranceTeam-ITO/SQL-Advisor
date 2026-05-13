package dbinc.sqladvisor.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 인증서 카테고리 열거형
 */
@Getter
@RequiredArgsConstructor
public enum Category {
    
    PROD("PROD", "운영 환경"),
    DEV("DEV", "개발 환경"),
    TEST("TEST", "테스트 환경");
    
    private final String code;
    private final String description;
    
    /**
     * 코드로 Category를 찾는 메서드
     */
    public static Category fromCode(String code) {
        for (Category category : values()) {
            if (category.getCode().equals(code)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown category code: " + code);
    }
}