package dbinc.sqladvisor.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import dbinc.sqladvisor.common.enums.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    
    private boolean success;
    private String message;
    private T data;
    private String errorCode;
    private LocalDateTime timestamp;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
            true,
            "요청이 성공적으로 처리되었습니다.",
            data,
            null,
            LocalDateTime.now()
        );
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(
            true,
            message,
            data,
            null,
            LocalDateTime.now()
        );
    }
    
    public static ApiResponse<Void> success(String message) {
        return new ApiResponse<>(
            true,
            message,
            null,
            null,
            LocalDateTime.now()
        );
    }


    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(
            true,
            "리소스가 성공적으로 생성되었습니다.",
            data,
            null,
            LocalDateTime.now()
        );
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return new ApiResponse<>(
            false,
            errorCode.getMessage(),
            null,
            errorCode.getCode(),
            LocalDateTime.now()
        );
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String customMessage) {
        return new ApiResponse<>(
            false,
            customMessage,
            null,
            errorCode.getCode(),
            LocalDateTime.now()
        );
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String... messageArgs) {
        return new ApiResponse<>(
            false,
            errorCode.getFormattedMessage(messageArgs),
            null,
            errorCode.getCode(),
            LocalDateTime.now()
        );
    }
    

    public static <T> ApiResponse<T> notFound(String message) {
        return error(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    public static <T> ApiResponse<T> badRequest(String message) {
        return error(ErrorCode.BAD_REQUEST, message);
    }

}