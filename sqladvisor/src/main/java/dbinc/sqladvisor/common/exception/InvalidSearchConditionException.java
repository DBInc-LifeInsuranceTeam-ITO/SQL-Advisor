package dbinc.sqladvisor.common.exception;

public class InvalidSearchConditionException extends RuntimeException {
    
    private final String condition;
    
    public InvalidSearchConditionException(String condition) {
        super(String.format("잘못된 검색 조건입니다: %s", condition));
        this.condition = condition;
    }
    
    public InvalidSearchConditionException(String condition, String message) {
        super(message);
        this.condition = condition;
    }
    
    public String getCondition() {
        return condition;
    }
}