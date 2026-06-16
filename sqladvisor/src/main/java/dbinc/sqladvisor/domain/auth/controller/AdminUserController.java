package dbinc.sqladvisor.domain.auth.controller;

import dbinc.sqladvisor.common.response.ApiResponse;
import dbinc.sqladvisor.domain.auth.dto.AuthDtos;
import dbinc.sqladvisor.domain.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AuthService authService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AuthDtos.UserSummaryResponse>>> users() {
        return ResponseEntity.ok(ApiResponse.success(authService.users()));
    }

    @PatchMapping("/{userId}/role")
    public ResponseEntity<ApiResponse<AuthDtos.UserSummaryResponse>> updateRole(
            @PathVariable Long userId,
            @RequestBody AuthDtos.UpdateUserRoleRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                authService.updateUserRole(userId, request == null ? null : request.role())
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.badRequest(exception.getMessage()));
    }
}