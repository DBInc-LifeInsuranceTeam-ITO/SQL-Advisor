package dbinc.sqladvisor.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.lang.reflect.Field;
import java.time.LocalDate;

public class DateRangeValidator implements ConstraintValidator<ValidDateRange, Object> {
    
    private String startField;
    private String endField;
    
    @Override
    public void initialize(ValidDateRange constraintAnnotation) {
        this.startField = constraintAnnotation.startField();
        this.endField = constraintAnnotation.endField();
    }
    
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        try {
            Field startFieldObj = value.getClass().getDeclaredField(startField);
            Field endFieldObj = value.getClass().getDeclaredField(endField);
            startFieldObj.setAccessible(true);
            endFieldObj.setAccessible(true);
            
            LocalDate startDate = (LocalDate) startFieldObj.get(value);
            LocalDate endDate = (LocalDate) endFieldObj.get(value);
            
            // 둘 다 null이거나 하나만 null인 경우는 유효함
            if (startDate == null || endDate == null) {
                return true;
            }
            
            // 시작일이 종료일보다 이후인 경우 유효하지 않음
            return !startDate.isAfter(endDate);
            
        } catch (Exception e) {
            // 필드를 찾을 수 없거나 접근할 수 없는 경우 유효한 것으로 처리
            return true;
        }
    }
}