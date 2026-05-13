package dbinc.sqladvisor.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 담당자 정보를 나타내는 공통 DTO
 * 인프라 담당자, 앱 담당자, 알림 대상자 등 모든 담당자 정보에 사용
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagerInfo {
    /**
     * 사번
     */
    private Integer empNo;
    
    /**
     * 이름
     */
    private String name;
    
    /**
     * 부서명
     */
    private String dept;
    
    /**
     * 전화번호 (선택적 - Detail 화면에서만 사용)
     */
    private String phone;
    
    /**
     * 이메일 (선택적 - Detail 화면에서만 사용)
     */
    private String email;
    
    /**
     * List 화면용 간단한 정보만 포함하는 생성자
     */
    public static ManagerInfo of(Integer empNo, String name, String dept) {
        return ManagerInfo.builder()
            .empNo(empNo)
            .name(name)
            .dept(dept)
            .build();
    }
    
    /**
     * Detail 화면용 전체 정보를 포함하는 생성자
     */
    public static ManagerInfo ofDetail(Integer empNo, String name, String dept, String phone, String email) {
        return ManagerInfo.builder()
            .empNo(empNo)
            .name(name)
            .dept(dept)
            .phone(phone)
            .email(email)
            .build();
    }
}