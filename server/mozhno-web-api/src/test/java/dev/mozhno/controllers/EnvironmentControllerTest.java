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

class EnvironmentControllerTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private Integer projectId;
    private String authToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        objectMapper = new ObjectMapper();

        jdbcTemplate.update(
            "INSERT INTO users (email, password_hash, role, status) VALUES (?, ?, ?, ?)",
            "env-test@test.com", passwordEncoder.encode("secret"), "admin", "active");

        Project p = new Project();
        p.setName("Test Project");
        projectId = projectRepository.save(p).getId();

        jdbcTemplate.update("UPDATE users SET project_id = ? WHERE email = ?", projectId, "env-test@test.com");

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"env-test@test.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        authToken = objectMapper.readTree(loginResponse).get("token").asText();
    }

    private String auth() {
        return "Bearer " + authToken;
    }

    @Test
    void getEnvironments_shouldReturnList() throws Exception {
        mockMvc.perform(get("/api/v1/environments")
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void createEnvironment_shouldReturnCreated() throws Exception {
        mockMvc.perform(post("/api/v1/environments")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"development\", \"color\": \"#3db8a5\", \"description\": \"Dev env\", \"requireActivationApproval\": true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("development"))
                .andExpect(jsonPath("$.color").value("#3db8a5"))
                .andExpect(jsonPath("$.description").value("Dev env"))
                .andExpect(jsonPath("$.requireActivationApproval").value(true));
    }

    @Test
    void createEnvironment_withoutColor_shouldReturnNullColor() throws Exception {
        mockMvc.perform(post("/api/v1/environments")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"qa\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("qa"))
                .andExpect(jsonPath("$.color").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void getEnvironment_shouldReturnEnvironment() throws Exception {
        Environment env = new Environment();
        env.setName("staging");
        env.setProjectId(projectId);
        Environment saved = environmentRepository.save(env);

        mockMvc.perform(get("/api/v1/environments/{id}", saved.getId())
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("staging"));
    }

    @Test
    void updateEnvironment_shouldReturnUpdated() throws Exception {
        Environment env = new Environment();
        env.setName("old");
        env.setProjectId(projectId);
        Environment saved = environmentRepository.save(env);

        mockMvc.perform(put("/api/v1/environments/{id}", saved.getId())
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"updated\", \"color\": \"#c08140\", \"requireActivationApproval\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("updated"))
                .andExpect(jsonPath("$.color").value("#c08140"))
                .andExpect(jsonPath("$.requireActivationApproval").value(true));
    }

    @Test
    void deleteEnvironment_shouldReturn204() throws Exception {
        Environment env = new Environment();
        env.setName("toDelete");
        env.setProjectId(projectId);
        Environment saved = environmentRepository.save(env);

        mockMvc.perform(delete("/api/v1/environments/{id}", saved.getId())
                .header("Authorization", auth()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteEnvironment_withLinkedApiKeys_shouldReturn400() throws Exception {
        Environment env = new Environment();
        env.setName("withKeys");
        env.setProjectId(projectId);
        Environment saved = environmentRepository.save(env);

        ApiKey k = new ApiKey();
        k.setProjectId(projectId);
        k.setEnvironmentId(saved.getId());
        k.setName("My Key");
        k.setApiKey("linked-key-" + System.currentTimeMillis());
        apiKeyRepository.save(k);

        mockMvc.perform(delete("/api/v1/environments/{id}", saved.getId())
                .header("Authorization", auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("API key(s) are scoped")));
    }
}
