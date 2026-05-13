package dbinc.sqladvisor.common.exception;

public class DuplicateAssetException extends RuntimeException {
    
    public DuplicateAssetException(String message) {
        super(message);
    }
    
    public DuplicateAssetException(String message, Throwable cause) {
        super(message, cause);
    }
}