package dbinc.sqladvisor.common.exception;

/**
 * 권한이 없는 사용자가 제한된 기능에 접근할 때 발생하는 예외
 */
public class UnauthorizedException extends RuntimeException {
    
    public UnauthorizedException(String message) {
        super(message);
    }
    
    public UnauthorizedException(String message, Throwable cause) {
        super(message, cause);
    }
}