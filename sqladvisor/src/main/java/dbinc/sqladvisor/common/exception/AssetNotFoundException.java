package dbinc.sqladvisor.common.exception;

public class AssetNotFoundException extends RuntimeException {
    
    private final String hostname;
    
    public AssetNotFoundException(String hostname) {
        super(String.format("인증서/라이선스를 찾을 수 없습니다. hostname: %s", hostname));
        this.hostname = hostname;
    }
    
    public String getHostname() {
        return hostname;
    }
}