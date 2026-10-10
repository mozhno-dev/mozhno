package dev.mozhno.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import dev.mozhno.BaseIntegrationTest;
import dev.mozhno.projects.Project;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IntegrationControllerTest extends BaseIntegrationTest {

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
        mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        objectMapper = new ObjectMapper();

        jdbcTemplate.update(
            "INSERT INTO users (email, password_hash, role, status) VALUES (?, ?, ?, ?)",
            "integration-test@test.com", passwordEncoder.encode("secret"), "admin", "active");

        Project p = new Project();
        p.setName("Test Project");
        projectId = projectRepository.save(p).getId();

        jdbcTemplate.update("UPDATE users SET project_id = ? WHERE email = ?", projectId, "integration-test@test.com");

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"integration-test@test.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        authToken = objectMapper.readTree(loginResponse).get("token").asText();
    }

    private String auth() {
        return "Bearer " + authToken;
    }

    @Test
    void getAllIntegrations_shouldReturnEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/integrations")
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void createIntegration_shouldReturnCreated() throws Exception {
        mockMvc.perform(post("/api/v1/integrations")
                .header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"custom_webhook\",\"name\":\"My Webhook\",\"enabled\":true,\"configJson\":\"{}\",\"eventSubscriptionsJson\":\"[]\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("My Webhook"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void getWebhookLimit_shouldReturnRemaining() throws Exception {
        mockMvc.perform(get("/api/v1/integrations/webhook-limit")
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").isNumber());
    }

    @Test
    void updateIntegration_shouldReturnUpdated() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String createResp = mockMvc.perform(post("/api/v1/integrations")
                .header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"custom_webhook\",\"name\":\"Original\",\"enabled\":true,\"configJson\":\"{}\",\"eventSubscriptionsJson\":\"[]\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int integrationId = om.readTree(createResp).get("id").asInt();

        mockMvc.perform(put("/api/v1/integrations/{id}", integrationId)
                .header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Updated Webhook\",\"enabled\":false,\"configJson\":\"{}\",\"eventSubscriptionsJson\":\"[]\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Webhook"));
    }

    @Test
    void getById_shouldReturnIntegration() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String createResp = mockMvc.perform(post("/api/v1/integrations")
                .header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"custom_webhook\",\"name\":\"Get Me\",\"enabled\":true,\"configJson\":\"{}\",\"eventSubscriptionsJson\":\"[]\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int integrationId = om.readTree(createResp).get("id").asInt();

        mockMvc.perform(get("/api/v1/integrations/{id}", integrationId)
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Get Me"));
    }

    @Test
    void deleteIntegration_shouldReturn204() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String createResp = mockMvc.perform(post("/api/v1/integrations")
                .header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"custom_webhook\",\"name\":\"Delete Me\",\"enabled\":true,\"configJson\":\"{}\",\"eventSubscriptionsJson\":\"[]\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int integrationId = om.readTree(createResp).get("id").asInt();

        mockMvc.perform(delete("/api/v1/integrations/{id}", integrationId)
                .header("Authorization", auth()))
                .andExpect(status().isNoContent());
    }
}
