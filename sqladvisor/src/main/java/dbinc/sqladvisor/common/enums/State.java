package dbinc.sqladvisor.common.enums;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public enum State {
    NORMAL("NORMAL", "정상"),
    IMMINENT_BEFORE_30D("IMMINENT_30D", "임박 30일전"),
    IMMINENT_BEFORE_7D("IMMINENT_7D", "임박 7일전"),
    EXPIRED("EXPIRED", "만료");
    
    private final String code;
    private final String description;
    
    State(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * 코드로 State를 찾는 메서드
     */
    public static State fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("State code cannot be null");
        }
        
        for (State state : values()) {
            if (state.getCode().equals(code)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown state code: " + code);
    }
    
    /**
     * Calculate state based on expiry date using default thresholds
     * @deprecated Use StateCalculator with configurable thresholds instead
     */
    @Deprecated
    public static State calculateState(LocalDate expiryDate) {
        LocalDate now = LocalDate.now();
        
        if (expiryDate.isBefore(now)) {
            return EXPIRED;
        }
        
        long daysUntilExpiry = ChronoUnit.DAYS.between(now, expiryDate);
        
        // Default thresholds for backward compatibility
        if (daysUntilExpiry <= 30 && daysUntilExpiry > 7) {
            return IMMINENT_BEFORE_30D;
        } else if (daysUntilExpiry <= 7) {
            return IMMINENT_BEFORE_7D;
        } else {
            return NORMAL;
        }
    }
}