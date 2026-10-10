package dev.mozhno.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import dev.mozhno.BaseIntegrationTest;
import dev.mozhno.apikeys.ApiKey;
import dev.mozhno.environments.Environment;
import dev.mozhno.projects.Project;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiKeyControllerTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private Integer projectId;
    private Integer environmentId;
    private String authToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        objectMapper = new ObjectMapper();

        jdbcTemplate.update(
            "INSERT INTO users (email, password_hash, role, status) VALUES (?, ?, ?, ?)",
            "apikey-test@test.com", passwordEncoder.encode("secret"), "admin", "active");

        Project p = new Project();
        p.setName("Test Project");
        projectId = projectRepository.save(p).getId();

        jdbcTemplate.update("UPDATE users SET project_id = ? WHERE email = ?", projectId, "apikey-test@test.com");

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"apikey-test@test.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        authToken = objectMapper.readTree(loginResponse).get("token").asText();

        Environment env = new Environment();
        env.setName("development");
        env.setProjectId(projectId);
        environmentId = environmentRepository.save(env).getId();
    }

    private String auth() {
        return "Bearer " + authToken;
    }

    @Test
    void getAllApiKeys_shouldReturnEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/api-keys")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void createApiKey_shouldReturnCreated() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(
                            "{\"name\": \"My Service\", \"environmentId\": %d, \"description\": \"Test service\"}",
                            environmentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My Service"));
    }

    @Test
    void createApiKey_withoutEnvironmentId_shouldReturn400() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"No Env\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getApiKey_shouldReturnApiKey() throws Exception {
        ApiKey k = new ApiKey();
        k.setProjectId(projectId);
        k.setName("Staging Service");
        k.setApiKey("test-key-1234567890");
        ApiKey saved = apiKeyRepository.save(k);

        mockMvc.perform(get("/api/v1/api-keys/{id}", saved.getId())
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Staging Service"));
    }

    @Test
    void getApiKey_shouldReturn404WhenNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/api-keys/9999")
                        .header("Authorization", auth()))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateApiKey_shouldReturnUpdated() throws Exception {
        ApiKey k = new ApiKey();
        k.setProjectId(projectId);
        k.setName("Original Name");
        k.setApiKey("original-key-1234567890");
        ApiKey saved = apiKeyRepository.save(k);

        mockMvc.perform(put("/api/v1/api-keys/{id}", saved.getId())
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(
                            "{\"name\": \"Updated Name\", \"environmentId\": %d, \"description\": \"Updated\"}",
                            environmentId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"));
    }

    @Test
    void deleteApiKey_shouldReturn204() throws Exception {
        ApiKey k = new ApiKey();
        k.setProjectId(projectId);
        k.setName("To Delete");
        k.setApiKey("delete-me-key-1234567890");
        ApiKey saved = apiKeyRepository.save(k);

        mockMvc.perform(delete("/api/v1/api-keys/{id}", saved.getId())
                        .header("Authorization", auth()))
                .andExpect(status().isNoContent());
    }
}
