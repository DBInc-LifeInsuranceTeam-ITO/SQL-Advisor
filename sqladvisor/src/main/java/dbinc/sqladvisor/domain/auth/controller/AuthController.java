package dbinc.sqladvisor.domain.auth.controller;

import dbinc.sqladvisor.common.response.ApiResponse;
import dbinc.sqladvisor.domain.auth.dto.AuthDtos;
import dbinc.sqladvisor.domain.auth.model.AppUserPrincipal;
import dbinc.sqladvisor.domain.auth.service.AuthService;
import dbinc.sqladvisor.domain.auth.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUserService currentUserService;

    @GetMapping("/config")
    public ResponseEntity<ApiResponse<AuthDtos.AuthConfigResponse>> config() {
        return ResponseEntity.ok(ApiResponse.success(authService.config()));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthDtos.CurrentUserResponse>> me() {
        AuthDtos.CurrentUserResponse response = currentUserService.currentUser()
                .map(authService::currentUserResponse)
                .orElseGet(AuthDtos.CurrentUserResponse::anonymous);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/google")
    public ResponseEntity<ApiResponse<AuthDtos.CurrentUserResponse>> google(
            @RequestBody AuthDtos.GoogleLoginRequest request,
            HttpServletRequest httpRequest
    ) {
        AppUserPrincipal principal = authService.authenticateGoogle(
                request == null ? null : request.credential(),
                httpRequest
        );
        setSecurityContext(principal, httpRequest);
        return ResponseEntity.ok(ApiResponse.success(authService.currentUserResponse(principal)));
    }

    @PostMapping("/local")
    public ResponseEntity<ApiResponse<AuthDtos.CurrentUserResponse>> local(
            @RequestBody AuthDtos.LocalLoginRequest request,
            HttpServletRequest httpRequest
    ) {
        AppUserPrincipal principal = authService.authenticateLocal(
                request == null ? null : request.identifier(),
                httpRequest
        );
        setSecurityContext(principal, httpRequest);
        return ResponseEntity.ok(ApiResponse.success(authService.currentUserResponse(principal)));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(ApiResponse.success("로그아웃되었습니다."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.badRequest(exception.getMessage()));
    }

    private void setSecurityContext(AppUserPrincipal principal, HttpServletRequest httpRequest) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.authorities()
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(httpRequest));
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        httpRequest.getSession(true).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                context
        );
    }
}
