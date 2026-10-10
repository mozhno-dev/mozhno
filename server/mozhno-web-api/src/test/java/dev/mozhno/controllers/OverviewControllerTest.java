package dev.mozhno.controllers;

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
import dev.mozhno.environments.Environment;
import dev.mozhno.flags.Flag;
import dev.mozhno.flags.FlagType;
import dev.mozhno.projects.Project;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OverviewControllerTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private Integer projectId;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        objectMapper = new ObjectMapper();

        Project p = new Project();
        p.setName("Overview Project");
        projectId = projectRepository.save(p).getId();

        Environment env = new Environment();
        env.setName("production");
        env.setProjectId(projectId);
        environmentRepository.save(env);

        Flag flag = new Flag();
        flag.setProjectId(projectId);
        flag.setName("Dark Mode");
        flag.setKey("dark-mode");
        flag.setFlagType(FlagType.RELEASE);
        flagRepository.save(flag);
    }

    private String loginAs(String email, String role) throws Exception {
        jdbcTemplate.update(
            "INSERT INTO users (email, password_hash, role, status) VALUES (?, ?, ?, ?)",
            email, passwordEncoder.encode("secret"), role, "active");
        jdbcTemplate.update("UPDATE users SET project_id = ? WHERE email = ?", projectId, email);
        String res = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(res).get("token").asText();
    }

    @Test
    void getOverview_returnsAggregatedStructure() throws Exception {
        String token = loginAs("admin-overview@test.com", "admin");

        mockMvc.perform(get("/api/v1/overview").header("Authorization", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totals.totalFlags").value(1))
            .andExpect(jsonPath("$.totals.archivedFlags").value(0))
            .andExpect(jsonPath("$.environments").isArray())
            .andExpect(jsonPath("$.environments[0].environmentName").value("production"))
            .andExpect(jsonPath("$.environments[0].totalFlags").value(1))
            .andExpect(jsonPath("$.onboarding.hasFlags").value(true))
            .andExpect(jsonPath("$.onboarding.hasEnvironments").value(true))
            .andExpect(jsonPath("$.recentActivity").isArray());
    }

    @Test
    void getOverview_isAccessibleToViewer() throws Exception {
        String token = loginAs("viewer-overview@test.com", "viewer");

        mockMvc.perform(get("/api/v1/overview").header("Authorization", token))
            .andExpect(status().isOk());
    }
}
