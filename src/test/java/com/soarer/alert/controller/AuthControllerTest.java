package com.soarer.alert.controller;

import com.soarer.alert.dto.ops.ApiResult;
import com.soarer.alert.entity.AuthUser;
import com.soarer.alert.security.AuthUserDetails;
import com.soarer.alert.service.auth.AuthUserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class AuthControllerTest {

    @Test
    void changePasswordReturnsFriendlyErrorForLockedAccount() {
        AuthUserService userService = mock(AuthUserService.class);
        AuthController controller = new AuthController(userService);
        AuthUser user = new AuthUser();
        user.setId(java.util.UUID.randomUUID());
        user.setUsername("ops-user");
        AuthUserDetails details = new AuthUserDetails(user);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                details,
                null,
                details.getAuthorities()
        );
        doThrow(new IllegalArgumentException("测试账号不允许改密码"))
                .when(userService)
                .changePassword(details, "old-password", "new-password");

        ResponseEntity<ApiResult<String>> response = controller.changePassword(
                new AuthController.ChangePasswordRequest("old-password", "new-password"),
                authentication
        );

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("测试账号不允许改密码");
    }
}
