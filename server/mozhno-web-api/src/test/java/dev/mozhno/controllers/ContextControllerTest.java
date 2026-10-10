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
import dev.mozhno.contexts.ContextDefinition;
import dev.mozhno.contexts.ContextValue;
import dev.mozhno.projects.Project;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContextControllerTest extends BaseIntegrationTest {

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
            "context-test@test.com", passwordEncoder.encode("secret"), "developer", "active");

        Project p = new Project();
        p.setName("Test Project");
        projectId = projectRepository.save(p).getId();

        jdbcTemplate.update("UPDATE users SET project_id = ? WHERE email = ?", projectId, "context-test@test.com");

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"context-test@test.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        authToken = objectMapper.readTree(loginResponse).get("token").asText();
    }

    private String auth() {
        return "Bearer " + authToken;
    }

    @Test
    void getDefinitions_shouldReturnList() throws Exception {
        mockMvc.perform(get("/api/v1/contexts")
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void createDefinition_shouldReturnCreated() throws Exception {
        mockMvc.perform(post("/api/v1/contexts")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"userId\", \"key\": \"user_id\", \"description\": \"User identifier\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("userId"));
    }

    @Test
    void getDefinition_shouldReturnDefinition() throws Exception {
        ContextDefinition ctx = new ContextDefinition();
        ctx.setName("appName");
        ctx.setProjectId(projectId);
        ContextDefinition saved = contextDefinitionRepository.save(ctx);

        mockMvc.perform(get("/api/v1/contexts/{id}", saved.getId())
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("appName"));
    }

    @Test
    void updateDefinition_shouldReturnUpdated() throws Exception {
        ContextDefinition ctx = new ContextDefinition();
        ctx.setName("original");
        ctx.setProjectId(projectId);
        ContextDefinition saved = contextDefinitionRepository.save(ctx);

        mockMvc.perform(put("/api/v1/contexts/{id}", saved.getId())
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"updated\", \"key\": \"updated_key\", \"description\": \"Updated description\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("updated"));
    }

    @Test
    void deleteDefinition_shouldReturn204() throws Exception {
        ContextDefinition ctx = new ContextDefinition();
        ctx.setName("toDelete");
        ctx.setProjectId(projectId);
        ContextDefinition saved = contextDefinitionRepository.save(ctx);

        mockMvc.perform(delete("/api/v1/contexts/{id}", saved.getId())
                .header("Authorization", auth()))
                .andExpect(status().isNoContent());
    }

    @Test
    void getValues_shouldReturnValues() throws Exception {
        ContextDefinition ctx = new ContextDefinition();
        ctx.setName("userId");
        ctx.setProjectId(projectId);
        ContextDefinition saved = contextDefinitionRepository.save(ctx);

        ContextValue cv = new ContextValue();
        cv.setContextDefinitionId(saved.getId());
        cv.setValues("[\"user1\",\"user2\"]");
        contextValueRepository.save(cv);

        mockMvc.perform(get("/api/v1/contexts/{id}/values", saved.getId())
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void createValue_shouldReturnCreated() throws Exception {
        ContextDefinition ctx = new ContextDefinition();
        ctx.setName("userId");
        ctx.setProjectId(projectId);
        ContextDefinition saved = contextDefinitionRepository.save(ctx);

        String json = "{\"contextDefinitionId\": " + saved.getId() + ", \"values\": \"[\\\"new-user\\\"]\"}";

        mockMvc.perform(post("/api/v1/contexts/{id}/values", saved.getId())
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated());
    }
}
