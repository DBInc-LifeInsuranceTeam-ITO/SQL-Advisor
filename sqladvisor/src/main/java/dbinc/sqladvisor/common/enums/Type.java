package dbinc.sqladvisor.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 인증서 타입 열거형
 */
@Getter
@RequiredArgsConstructor
public enum Type {
    
    SSL("SSL", "SSL 인증서"),
    LICENSE("LICENSE", "라이선스"),
    ETC("ETC", "기타");
    
    private final String code;
    private final String description;
    
    public static Type fromCode(String code) {
        for (Type type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown type code: " + code);
    }
}