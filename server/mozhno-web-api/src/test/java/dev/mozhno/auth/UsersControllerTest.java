package dev.mozhno.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import dev.mozhno.BaseIntegrationTest;
import dev.mozhno.projects.Project;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UsersControllerTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private String authToken;
    private Integer testProjectId;
    private Integer secondProjectId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        objectMapper = new ObjectMapper();

        Project p = new Project();
        p.setName("Test Project");
        testProjectId = projectRepository.save(p).getId();

        Project p2 = new Project();
        p2.setName("Other Project");
        secondProjectId = projectRepository.save(p2).getId();

        jdbcTemplate.update(
            "INSERT INTO users (email, password_hash, role, status, project_id) VALUES (?, ?, ?, ?, ?)",
            "users-admin@test.com", passwordEncoder.encode("secret"), "admin", "active", testProjectId);

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"users-admin@test.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        authToken = objectMapper.readTree(loginResponse).get("token").asText();
    }

    private String auth() {
        return "Bearer " + authToken;
    }

    private int insertUser(String email, String role) {
        return insertUserInProject(email, role, testProjectId);
    }

    private int insertUserInProject(String email, String role, Integer projectId) {
        jdbcTemplate.update(
            "INSERT INTO users (email, password_hash, role, status, project_id) VALUES (?, ?, ?, ?, ?)",
            email, passwordEncoder.encode("Pass1234!"), role, "active", projectId);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Integer.class, email);
    }

    @Test
    void getAllUsers_shouldReturnList() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getAllUsers_shouldReturnOnlyUsersOfOwnProject() throws Exception {
        insertUser("own-user@test.com", "viewer");
        insertUserInProject("foreign-user@test.com", "viewer", secondProjectId);

        mockMvc.perform(get("/api/v1/users")
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", hasItem("own-user@test.com")))
                .andExpect(jsonPath("$[*].email", not(hasItem("foreign-user@test.com"))));
    }

    @Test
    void getById_shouldReturn404ForUserInAnotherProject() throws Exception {
        int foreignId = insertUserInProject("foreign-get@test.com", "viewer", secondProjectId);

        mockMvc.perform(get("/api/v1/users/{id}", foreignId)
                .header("Authorization", auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateUser_shouldReturn404ForUserInAnotherProject() throws Exception {
        int foreignId = insertUserInProject("foreign-update@test.com", "viewer", secondProjectId);

        mockMvc.perform(put("/api/v1/users/{id}", foreignId)
                .header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"admin\"}"))
                .andExpect(status().isNotFound());

        assertEquals("viewer",
            jdbcTemplate.queryForObject("SELECT role FROM users WHERE id = ?", String.class, foreignId));
    }

    @Test
    void deleteUser_shouldReturn404ForUserInAnotherProject() throws Exception {
        int foreignId = insertUserInProject("foreign-delete@test.com", "viewer", secondProjectId);

        mockMvc.perform(delete("/api/v1/users/{id}", foreignId)
                .header("Authorization", auth()))
                .andExpect(status().isNotFound());

        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, foreignId));
    }

    @Test
    void uploadAvatar_shouldReturn404ForUserInAnotherProject() throws Exception {
        int foreignId = insertUserInProject("foreign-avatar@test.com", "viewer", secondProjectId);
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/v1/users/{id}/avatar", foreignId)
                .file(file)
                .header("Authorization", auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAvatar_shouldReturnImageForUserInOwnProject() throws Exception {
        int userId = insertUser("avatar-owner@test.com", "viewer");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        jdbcTemplate.update("UPDATE users SET avatar = 'blob.png', avatar_data = ? WHERE id = ?",
            png, userId);

        mockMvc.perform(get("/api/v1/users/{id}/avatar", userId)
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(png))
                .andExpect(header().string("Content-Type", "image/png"));
    }

    @Test
    void getAvatar_shouldReturn404ForUserInAnotherProject() throws Exception {
        int foreignId = insertUserInProject("foreign-get-avatar@test.com", "viewer", secondProjectId);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        jdbcTemplate.update("UPDATE users SET avatar = 'blob.png', avatar_data = ? WHERE id = ?",
            png, foreignId);

        mockMvc.perform(get("/api/v1/users/{id}/avatar", foreignId)
                .header("Authorization", auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getAvatar_shouldReturn401WhenUnauthenticated() throws Exception {
        int userId = insertUser("avatar-anon@test.com", "viewer");

        mockMvc.perform(get("/api/v1/users/{id}/avatar", userId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getById_shouldReturnUser() throws Exception {
        int userId = insertUser("getuser@test.com", "viewer");

        mockMvc.perform(get("/api/v1/users/{id}", userId)
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("getuser@test.com"));
    }

    @Test
    void getById_shouldReturn404WhenNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/users/99999")
                .header("Authorization", auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateUser_shouldReturnUpdatedUser() throws Exception {
        int userId = insertUser("update@test.com", "viewer");

        mockMvc.perform(put("/api/v1/users/{id}", userId)
                .header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"developer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("developer"));
    }

    @Test
    void deleteUser_shouldReturn204() throws Exception {
        int userId = insertUser("todelete@test.com", "viewer");

        mockMvc.perform(delete("/api/v1/users/{id}", userId)
                .header("Authorization", auth()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteSelf_shouldReturn400AndKeepUser() throws Exception {
        Integer adminId = jdbcTemplate.queryForObject(
            "SELECT id FROM users WHERE email = ?", Integer.class, "users-admin@test.com");

        mockMvc.perform(delete("/api/v1/users/{id}", adminId)
                .header("Authorization", auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_DELETE_SELF"));

        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, adminId));
    }

    @Test
    void invite_shouldReturn201AndMessage() throws Exception {
        mockMvc.perform(post("/api/v1/users/invite")
                .header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"invited@test.com\",\"role\":\"developer\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Invitation sent to invited@test.com"));
    }

    @Test
    void invite_shouldReturn400ForDuplicateEmail() throws Exception {
        jdbcTemplate.update(
            "INSERT INTO users (email, password_hash, role, status, project_id) VALUES (?, ?, ?, ?, ?)",
            "existing@test.com", passwordEncoder.encode("pass"), "developer", "active", testProjectId);

        mockMvc.perform(post("/api/v1/users/invite")
                .header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"existing@test.com\",\"role\":\"viewer\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void invite_shouldReturn400ForInvalidRole() throws Exception {
        mockMvc.perform(post("/api/v1/users/invite")
                .header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"rolebad@test.com\",\"role\":\"superadmin\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invite_shouldReturn403ForNonAdmin() throws Exception {
        jdbcTemplate.update(
            "INSERT INTO users (email, password_hash, role, status, project_id) VALUES (?, ?, ?, ?, ?)",
            "viewer-user@test.com", passwordEncoder.encode("viewpass"), "viewer", "active", testProjectId);

        String viewerLoginResp = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"viewer-user@test.com\",\"password\":\"viewpass\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(viewerLoginResp).get("token").asText();

        mockMvc.perform(post("/api/v1/users/invite")
                .header("Authorization", "Bearer " + viewerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nomail@test.com\",\"role\":\"viewer\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sendResetLink_shouldReturn200() throws Exception {
        int userId = insertUser("resetme@test.com", "viewer");

        mockMvc.perform(post("/api/v1/users/{id}/send-reset-link", userId)
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password reset link sent"));
    }

    @Test
    void sendResetLink_shouldReturn404ForUnknownUser() throws Exception {
        mockMvc.perform(post("/api/v1/users/99999/send-reset-link")
                .header("Authorization", auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sendResetLink_shouldReturn404ForUserInAnotherProject() throws Exception {
        int foreignId = insertUserInProject("foreign-reset@test.com", "viewer", secondProjectId);

        mockMvc.perform(post("/api/v1/users/{id}/send-reset-link", foreignId)
                .header("Authorization", auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void sendResetLink_shouldReturn403ForNonAdmin() throws Exception {
        jdbcTemplate.update(
            "INSERT INTO users (email, password_hash, role, status, project_id) VALUES (?, ?, ?, ?, ?)",
            "viewer2@test.com", passwordEncoder.encode("viewpass"), "viewer", "active", testProjectId);

        String viewerLoginResp = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"viewer2@test.com\",\"password\":\"viewpass\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(viewerLoginResp).get("token").asText();

        mockMvc.perform(post("/api/v1/users/1/send-reset-link")
                .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }
}
