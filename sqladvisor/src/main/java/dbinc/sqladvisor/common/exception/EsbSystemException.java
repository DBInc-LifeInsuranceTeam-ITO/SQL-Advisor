package dbinc.sqladvisor.common.exception;

import dbinc.sqladvisor.common.enums.ErrorCode;

public class EsbSystemException extends RuntimeException {
    
    private final ErrorCode errorCode;
    
    public EsbSystemException(String message) {
        super(message);
        this.errorCode = ErrorCode.ESB_SYSTEM_ERROR;
    }
    
    public EsbSystemException(String message, ErrorCode errorCode) {
        super(message);
        this.errorCode = errorCode;
    }
    
    public EsbSystemException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ErrorCode.ESB_SYSTEM_ERROR;
    }
    
    public EsbSystemException(String message, Throwable cause, ErrorCode errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
    }
    
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}