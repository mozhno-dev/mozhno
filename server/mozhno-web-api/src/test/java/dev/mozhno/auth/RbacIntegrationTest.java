package dev.mozhno.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import dev.mozhno.BaseIntegrationTest;
import dev.mozhno.contexts.ContextDefinition;
import dev.mozhno.projects.Project;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("RBAC integration tests")
class RbacIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private Integer projectId;

    private String adminToken;
    private String developerToken;
    private String developerRefreshToken;
    private String viewerToken;
    private int developerUserId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        objectMapper = new ObjectMapper();

        Project p = new Project();
        p.setName("RBAC Test Project");
        projectId = projectRepository.save(p).getId();

        adminToken = loginAs("rbac-admin@test.com", "admin");

        String devLoginResp = loginAndReturnRaw("rbac-dev@test.com", "developer");
        developerToken = objectMapper.readTree(devLoginResp).get("token").asText();
        developerRefreshToken = objectMapper.readTree(devLoginResp).get("refreshToken").asText();
        developerUserId = objectMapper.readTree(devLoginResp).get("user").get("id").asInt();

        viewerToken = loginAs("rbac-viewer@test.com", "viewer");
    }

    private String loginAs(String email, String role) throws Exception {
        return objectMapper.readTree(loginAndReturnRaw(email, role)).get("token").asText();
    }

    private String loginAndReturnRaw(String email, String role) throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role, status) VALUES (?, ?, ?, ?)",
                email, passwordEncoder.encode("Admin1!"), role, "active");
        jdbcTemplate.update("UPDATE users SET project_id = ? WHERE email = ?", projectId, email);
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Admin1!\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String adminAuth() { return "Bearer " + adminToken; }
    private String devAuth() { return "Bearer " + developerToken; }
    private String viewerAuth() { return "Bearer " + viewerToken; }

    // ──────────────────────────────────────────────
    //   ADMIN tests (all allowed)
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN: can create flag")
    void adminCreateFlag() throws Exception {
        mockMvc.perform(post("/api/v1/flags")
                        .header("Authorization", adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Admin Flag\",\"key\":\"admin-flag\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Admin Flag"));
    }

    @Test
    @DisplayName("ADMIN: can invite user")
    void adminInviteUser() throws Exception {
        mockMvc.perform(post("/api/v1/users/invite")
                        .header("Authorization", adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin-invited@test.com\",\"role\":\"developer\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Invitation sent to admin-invited@test.com"));
    }

    @Test
    @DisplayName("ADMIN: can create webhook")
    void adminCreateWebhook() throws Exception {
        mockMvc.perform(post("/api/v1/integrations")
                        .header("Authorization", adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"custom_webhook\",\"name\":\"Admin WH\",\"enabled\":false," +
                                "\"configJson\":\"{\\\"url\\\":\\\"https://example.com/webhook\\\"}\"," +
                                "\"eventSubscriptionsJson\":\"[]\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Admin WH"));
    }

    @Test
    @DisplayName("ADMIN: can change project settings")
    void adminChangeProject() throws Exception {
        mockMvc.perform(put("/api/v1/projects")
                        .header("Authorization", adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"RBAC Updated\",\"description\":\"changed by admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("RBAC Updated"));
    }

    @Test
    @DisplayName("ADMIN: can delete user")
    void adminDeleteUser() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role, status, project_id) VALUES (?, ?, ?, ?, ?)",
                "todelete@test.com", passwordEncoder.encode("DelPass1!"), "viewer", "active", projectId);
        int id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Integer.class, "todelete@test.com");

        mockMvc.perform(delete("/api/v1/users/{id}", id)
                        .header("Authorization", adminAuth()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("ADMIN: cannot access users of another project (404)")
    void adminCannotAccessUsersOfAnotherProject() throws Exception {
        Project other = new Project();
        other.setName("Other Project");
        Integer otherProjectId = projectRepository.save(other).getId();

        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role, status, project_id) VALUES (?, ?, ?, ?, ?)",
                "foreign-user@test.com", passwordEncoder.encode("Foreign1!"), "viewer", "active", otherProjectId);
        int foreignId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Integer.class, "foreign-user@test.com");

        mockMvc.perform(get("/api/v1/users/{id}", foreignId)
                        .header("Authorization", adminAuth()))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/users/{id}", foreignId)
                        .header("Authorization", adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"admin\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/users/{id}", foreignId)
                        .header("Authorization", adminAuth()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/users/{id}/send-reset-link", foreignId)
                        .header("Authorization", adminAuth()))
                .andExpect(status().isNotFound());

        assertEquals("viewer", jdbcTemplate.queryForObject(
                "SELECT role FROM users WHERE id = ?", String.class, foreignId));
    }

    // ──────────────────────────────────────────────
    //   DEVELOPER tests (flags/segments OK, rest 403)
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("DEVELOPER: can create flag")
    void developerCreateFlag() throws Exception {
        mockMvc.perform(post("/api/v1/flags")
                        .header("Authorization", devAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dev Flag\",\"key\":\"dev-flag\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("DEVELOPER: can create segment")
    void developerCreateSegment() throws Exception {
        ContextDefinition cd = new ContextDefinition();
        cd.setName("appName");
        cd.setProjectId(projectId);
        Integer contextDefId = contextDefinitionRepository.save(cd).getId();

        mockMvc.perform(post("/api/v1/segments")
                        .header("Authorization", devAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Dev Segment\",\"context\":[{\"contextDefinitionId\":" +
                                contextDefId + ",\"operator\":\"in\",\"contextValues\":\"web,mobile\"}]}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("DEVELOPER: cannot invite user (403)")
    void developerCannotInviteUser() throws Exception {
        mockMvc.perform(post("/api/v1/users/invite")
                        .header("Authorization", devAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"no@test.com\",\"role\":\"viewer\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DEVELOPER: cannot delete user (403)")
    void developerCannotDeleteUser() throws Exception {
        mockMvc.perform(delete("/api/v1/users/1")
                        .header("Authorization", devAuth()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DEVELOPER: cannot create webhook (403)")
    void developerCannotCreateWebhook() throws Exception {
        mockMvc.perform(post("/api/v1/integrations")
                        .header("Authorization", devAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"custom_webhook\",\"name\":\"Dev WH\",\"enabled\":false," +
                                "\"configJson\":\"{\\\"url\\\":\\\"https://example.com/webhook\\\"}\"," +
                                "\"eventSubscriptionsJson\":\"[]\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DEVELOPER: cannot change project (403)")
    void developerCannotChangeProject() throws Exception {
        mockMvc.perform(put("/api/v1/projects")
                        .header("Authorization", devAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isForbidden());
    }

    // ──────────────────────────────────────────────
    //   VIEWER tests (read-only)
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("VIEWER: cannot create flag (403)")
    void viewerCannotCreateFlag() throws Exception {
        mockMvc.perform(post("/api/v1/flags")
                        .header("Authorization", viewerAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Viewer Flag\",\"key\":\"viewer-flag\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("VIEWER: cannot create API key (403)")
    void viewerCannotCreateApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", viewerAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Viewer Key\",\"environmentId\":1}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("VIEWER: can view flags (200)")
    void viewerCanViewFlags() throws Exception {
        mockMvc.perform(get("/api/v1/flags")
                        .header("Authorization", viewerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    @DisplayName("VIEWER: can view audit log (200)")
    void viewerCanViewAudit() throws Exception {
        mockMvc.perform(get("/api/v1/audit")
                        .header("Authorization", viewerAuth()))
                .andExpect(status().isOk());
    }

    // ──────────────────────────────────────────────
    //   Role change: DEVELOPER → VIEWER
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("Role change: old JWT retains privileges until refresh")
    void roleChange_oldJwtRetainsPrivilegesUntilRefresh() throws Exception {
        mockMvc.perform(post("/api/v1/flags")
                        .header("Authorization", devAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Before Role Change\",\"key\":\"before-change\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/v1/users/{id}", developerUserId)
                        .header("Authorization", adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"viewer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("viewer"));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", devAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("viewer"));

        mockMvc.perform(post("/api/v1/flags")
                        .header("Authorization", devAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"After Change Old Token\",\"key\":\"after-change-old\"}"))
                .andExpect(status().isCreated());

        String refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + developerRefreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newToken = objectMapper.readTree(refreshed).get("token").asText();

        mockMvc.perform(post("/api/v1/flags")
                        .header("Authorization", "Bearer " + newToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"After Refresh\",\"key\":\"after-refresh\"}"))
                .andExpect(status().isForbidden());
    }

    // ──────────────────────────────────────────────
    //   Suspended user
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("Suspended user: login is rejected (401 Unauthorized)")
    void suspendedUserLoginRejected() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role, status) VALUES (?, ?, ?, ?)",
                "suspended@test.com", passwordEncoder.encode("SuspPass1!"), "developer", "active");
        jdbcTemplate.update("UPDATE users SET project_id = ? WHERE email = ?", projectId, "suspended@test.com");

        jdbcTemplate.update("UPDATE users SET status = 'suspended' WHERE email = ?", "suspended@test.com");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"suspended@test.com\",\"password\":\"SuspPass1!\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ──────────────────────────────────────────────
    //   Account lockout (brute-force protection)
    // ──────────────────────────────────────────────

    @Autowired
    private dev.mozhno.security.SecurityProperties securityProperties;

    @Test
    @DisplayName("Account lockout: exceeding max failed logins locks the account")
    void accountLockoutAfterFiveFailedLogins() throws Exception {
        int maxAttempts = securityProperties.getMaxFailedLoginAttempts();

        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role, status) VALUES (?, ?, ?, ?)",
                "locked@test.com", passwordEncoder.encode("LockPass1!"), "developer", "active");
        jdbcTemplate.update("UPDATE users SET project_id = ? WHERE email = ?", projectId, "locked@test.com");

        jdbcTemplate.update("UPDATE users SET failed_login_attempts = ? WHERE email = ?",
                maxAttempts, "locked@test.com");
        jdbcTemplate.update("UPDATE users SET locked_until = ? WHERE email = ?",
                java.sql.Timestamp.from(java.time.Instant.now().plus(15, java.time.temporal.ChronoUnit.MINUTES)),
                "locked@test.com");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"locked@test.com\",\"password\":\"LockPass1!\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ──────────────────────────────────────────────
    //   Admin self-demotion prevention
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("Admin cannot change own role when they have created other users")
    void adminCannotDemoteSelfWhenHasChildren() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role, status, project_id) VALUES (?, ?, ?, ?, ?)",
                "admin2@test.com", passwordEncoder.encode("Admin2Pass1!"), "admin", "active", projectId);
        int admin2Id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Integer.class, "admin2@test.com");

        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role, status, project_id, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                "child@test.com", passwordEncoder.encode("ChildPass1!"), "viewer", "active", projectId, admin2Id);

        String admin2Raw = loginAndReturnRawForUser("admin2@test.com", "Admin2Pass1!");
        String admin2Token = objectMapper.readTree(admin2Raw).get("token").asText();

        mockMvc.perform(put("/api/v1/users/{id}", admin2Id)
                        .header("Authorization", "Bearer " + admin2Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"viewer\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("Cannot remove the last admin")));
    }

    // ──────────────────────────────────────────────
    //   Password change invalidates refresh tokens
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("Password change: refresh token is revoked")
    void passwordChangeRevokesRefreshToken() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role, status) VALUES (?, ?, ?, ?)",
                "pwdchange@test.com", passwordEncoder.encode("PwdOld1!"), "developer", "active");
        jdbcTemplate.update("UPDATE users SET project_id = ? WHERE email = ?", projectId, "pwdchange@test.com");
        int uid = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Integer.class, "pwdchange@test.com");

        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pwdchange@test.com\",\"password\":\"PwdOld1!\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String rt = objectMapper.readTree(loginResp).get("refreshToken").asText();

        String rawResetToken = "pwd-reset-token";
        String tokenHash = dev.mozhno.client.HashUtils.sha256(rawResetToken);
        jdbcTemplate.update(
                "INSERT INTO password_reset_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
                uid, tokenHash, java.sql.Timestamp.from(java.time.Instant.now().plus(1, java.time.temporal.ChronoUnit.HOURS)));

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + rawResetToken + "\",\"password\":\"PwdNew2!\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rt + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ──────────────────────────────────────────────
    //   Refresh token reuse detection
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("Refresh token reuse: double use revokes entire family")
    void refreshTokenReuseRevokesFamily() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, role, status) VALUES (?, ?, ?, ?)",
                "reuse@test.com", passwordEncoder.encode("ReusePass1!"), "developer", "active");
        jdbcTemplate.update("UPDATE users SET project_id = ? WHERE email = ?", projectId, "reuse@test.com");

        String loginResp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reuse@test.com\",\"password\":\"ReusePass1!\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String rt = objectMapper.readTree(loginResp).get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rt + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rt + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ──────────────────────────────────────────────
    //   Unauthenticated access
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("Unauthenticated: all protected endpoints return 401")
    void unauthenticatedReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/flags"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized());
    }

    // ──────────────────────────────────────────────
    //   Helpers
    // ──────────────────────────────────────────────

    private String loginAndReturnRawForUser(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
