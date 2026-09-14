package org.example.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityConfigEnabledTest.TestController.class)
@Import({SecurityConfig.class, ApiAuthProperties.class, CorsProperties.class,
        SecurityConfigEnabledTest.TestController.class})
@TestPropertySource(properties = {
        "app.auth.admin-username=admin",
        "app.auth.admin-initial-password=initial-admin-password",
        "app.cors.allowed-origins=http://localhost:9900"
})
class SecurityConfigEnabledTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsApiRequestWithoutLogin() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void acceptsApiRequestWithOpsRole() throws Exception {
        mockMvc.perform(get("/api/ping").with(SecurityMockMvcRequestPostProcessors.user("ops").roles("OPS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("pong"));
    }

    @Test
    void rejectsAdminApiWithoutAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/users").with(SecurityMockMvcRequestPostProcessors.user("ops").roles("OPS")))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsWebSocketWithoutLogin() throws Exception {
        mockMvc.perform(get("/ws/diagnosis"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void redirectsUnauthenticatedPageToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login.html"));
    }

    @Test
    void redirectsUnknownStaticResourceToLogin() throws Exception {
        mockMvc.perform(get("/styles.css"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login.html"));
    }

    @Test
    void rejectsAdminPageWithoutAdminRole() throws Exception {
        mockMvc.perform(get("/admin.html").with(SecurityMockMvcRequestPostProcessors.user("ops").roles("OPS")))
                .andExpect(status().isForbidden());
    }

    @org.springframework.web.bind.annotation.RestController
    static class TestController {

        @org.springframework.web.bind.annotation.GetMapping("/api/ping")
        ApiResponse pong() {
            return new ApiResponse("pong");
        }
    }

    record ApiResponse(String data) {
    }
}
