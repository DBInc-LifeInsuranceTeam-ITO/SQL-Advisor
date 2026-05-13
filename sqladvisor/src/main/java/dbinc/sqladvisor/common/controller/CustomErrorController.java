package dbinc.sqladvisor.common.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 404 에러 처리 컨트롤러
 * Super Admin URL이 아닌 잘못된 경로 접근 시 처리
 */
@Slf4j
@Controller
public class CustomErrorController implements ErrorController {
    
    @RequestMapping("/error")
    public String handleError(HttpServletRequest request) {
        Object status = request.getAttribute("javax.servlet.error.status_code");
        
        if (status != null) {
            int statusCode = Integer.parseInt(status.toString());
            
            if (statusCode == HttpStatus.NOT_FOUND.value()) {
                String requestUri = (String) request.getAttribute("javax.servlet.error.request_uri");
                log.debug("404 Error - Requested URI: {}", requestUri);
                
                // Super Admin과 유사한 패턴의 URL 접근 시도 경고
                if (requestUri != null && requestUri.length() >= 10 && 
                    requestUri.matches(".*[a-z].*") && requestUri.matches(".*[0-9].*")) {
                    log.warn("Suspicious Super Admin access attempt: {}", requestUri);
                }
            }
        }
        
        // Vue 앱의 404 페이지로 포워딩
        return "forward:/index.html";
    }
}