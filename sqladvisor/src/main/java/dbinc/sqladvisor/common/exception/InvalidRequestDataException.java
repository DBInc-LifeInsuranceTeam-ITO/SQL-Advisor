package dbinc.sqladvisor.common.exception;

import lombok.Getter;

@Getter
public class InvalidRequestDataException extends RuntimeException {
    private final String field;
    
    public InvalidRequestDataException(String field, String message) {
        super(message);
        this.field = field;
    }
}