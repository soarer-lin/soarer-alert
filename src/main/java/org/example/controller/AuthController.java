package org.example.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.example.dto.ops.ApiResult;
import org.example.entity.AuthLoginAudit;
import org.example.entity.AuthUser;
import org.example.security.AuthUserDetails;
import org.example.service.auth.AuthUserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthUserService userService;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(AuthUserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResult<CurrentUserResponse>> login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        try {
            Authentication authentication = userService.login(
                    request.username(),
                    request.password(),
                    httpRequest.getRemoteAddr(),
                    httpRequest.getHeader("User-Agent")
            );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(
                    context,
                    httpRequest,
                    httpResponse
            );
            AuthUserDetails details = (AuthUserDetails) authentication.getPrincipal();
            return ResponseEntity.ok(ApiResult.success(toCurrentUser(details, true)));
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            return ResponseEntity.status(401).body(ApiResult.error(401, exception.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResult<String>> logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(ApiResult.success("已退出登录"));
    }

    @PostMapping("/demo-credentials")
    public ApiResult<DemoCredentialsResponse> demoCredentials() {
        AuthUserService.DemoLoginCredentials credentials = userService.issueDemoLoginCredentials();
        if (credentials == null) {
            return ApiResult.success(new DemoCredentialsResponse(false, null, null));
        }
        return ApiResult.success(new DemoCredentialsResponse(
                true,
                credentials.username(),
                credentials.password()
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResult<CurrentUserResponse>> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthUserDetails details)) {
            return ResponseEntity.status(401).body(ApiResult.error(401, "Unauthorized"));
        }
        return ResponseEntity.ok(ApiResult.success(toCurrentUser(details, true)));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResult<String>> changePassword(
            @RequestBody ChangePasswordRequest request,
            Authentication authentication
    ) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthUserDetails details)) {
            return ResponseEntity.status(401).body(ApiResult.error(401, "Unauthorized"));
        }
        try {
            userService.changePassword(details, request.oldPassword(), request.newPassword());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(ApiResult.error(400, exception.getMessage()));
        }
        return ResponseEntity.ok(ApiResult.success("密码已更新"));
    }

    private CurrentUserResponse toCurrentUser(AuthUserDetails details, boolean authenticated) {
        AuthUser user = details.user();
        return new CurrentUserResponse(
                authenticated,
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                Boolean.TRUE.equals(user.getMustChangePassword())
        );
    }

    public record LoginRequest(String username, String password) {
    }

    public record ChangePasswordRequest(String oldPassword, String newPassword) {
    }

    public record DemoCredentialsResponse(
            boolean enabled,
            String username,
            String password
    ) {
    }

    public record CurrentUserResponse(
            boolean authenticated,
            String username,
            String displayName,
            String role,
            boolean mustChangePassword
    ) {
    }
}
