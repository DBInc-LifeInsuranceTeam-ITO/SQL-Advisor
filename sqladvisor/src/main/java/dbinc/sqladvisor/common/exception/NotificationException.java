package dbinc.sqladvisor.common.exception;

import dbinc.sqladvisor.common.enums.ErrorCode;

public class NotificationException extends RuntimeException {
    
    private final ErrorCode errorCode;
    
    public NotificationException(String message) {
        super(message);
        this.errorCode = ErrorCode.NOTIFICATION_SEND_ERROR;
    }
    
    public NotificationException(String message, ErrorCode errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
    
    public NotificationException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ErrorCode.NOTIFICATION_SEND_ERROR;
    }
    
    public NotificationException(String message, Throwable cause, ErrorCode errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
    }
    
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}