package dbinc.sqladvisor.common.exception;

import lombok.Getter;

@Getter
public class AssetDeletionRestrictedException extends RuntimeException {
    private final String hostname;
    
    public AssetDeletionRestrictedException(String hostname) {
        super("인증서/라이선스 삭제가 제한되었습니다: " + hostname);
        this.hostname = hostname;
    }
}