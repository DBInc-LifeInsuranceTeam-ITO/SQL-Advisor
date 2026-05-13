package dbinc.sqladvisor.common.constants;

import java.util.List;

/**
 * 알림 관련 상수 정의
 */
public final class NotificationConstants {
    
    private NotificationConstants() {
        throw new AssertionError("Cannot instantiate constants class");
    }
    
    /**
     * 인증서 만료 알림 기간 (일 단위)
     * 30일, 15일, 7일, 1일 전에 알림 발송
     */
    public static final List<Integer> NOTIFICATION_PERIODS = List.of(30, 15, 7, 1);
    
    /**
     * 인증서 상태 계산 임계값 (일 단위)
     */
    public static final class StateThresholds {
        public static final int IMMINENT_30_DAYS = 30;
        public static final int IMMINENT_15_DAYS = 15;
        public static final int IMMINENT_7_DAYS = 7;
        public static final int IMMINENT_1_DAY = 1;
        
        private StateThresholds() {
            throw new AssertionError("Cannot instantiate constants class");
        }
    }
}