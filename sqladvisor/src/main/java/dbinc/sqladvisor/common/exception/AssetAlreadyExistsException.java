package dbinc.sqladvisor.common.exception;

public class AssetAlreadyExistsException extends RuntimeException {
    
    private final String hostname;
    
    public AssetAlreadyExistsException(String hostname) {
        super(String.format("이미 존재하는 인증서/라이선스입니다. hostname: %s", hostname));
        this.hostname = hostname;
    }
    
    public String getHostname() {
        return hostname;
    }
}