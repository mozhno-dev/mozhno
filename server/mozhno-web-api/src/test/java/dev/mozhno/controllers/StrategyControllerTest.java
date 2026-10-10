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
import dev.mozhno.environments.Environment;
import dev.mozhno.flags.Flag;
import dev.mozhno.flags.FlagType;
import dev.mozhno.flags.strategy.FlagStrategy;
import dev.mozhno.projects.Project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StrategyControllerTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private Integer flagId;
    private Integer projectId;
    private Integer environmentId;
    private String authToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        objectMapper = new ObjectMapper();

        jdbcTemplate.update(
            "INSERT INTO users (email, password_hash, role, status) VALUES (?, ?, ?, ?)",
            "strategy-test@test.com", passwordEncoder.encode("secret"), "developer", "active");

        Project p = new Project();
        p.setName("Test Project");
        Project saved = projectRepository.save(p);
        projectId = saved.getId();

        jdbcTemplate.update("UPDATE users SET project_id = ? WHERE email = ?", projectId, "strategy-test@test.com");

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"strategy-test@test.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        authToken = objectMapper.readTree(loginResponse).get("token").asText();

        Environment env = new Environment();
        env.setName("development");
        env.setProjectId(saved.getId());
        environmentId = environmentRepository.save(env).getId();

        Flag flag = new Flag();
        flag.setName("Test Flag");
        flag.setKey("test-flag");
        flag.setProjectId(saved.getId());
        flag.setFlagType(FlagType.RELEASE);
        flagId = flagRepository.save(flag).getId();
    }

    private String auth() {
        return "Bearer " + authToken;
    }

    @Test
    void getStrategies_shouldReturnList() throws Exception {
        mockMvc.perform(get("/api/v1/flags/{flagId}/strategies", flagId)
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void createStrategy_shouldReturnCreated() throws Exception {
        String json = "{" +
            "\"flagId\": " + flagId + "," +
            "\"environmentId\": " + environmentId + "," +
            "\"enabled\": true" +
            "}";

        mockMvc.perform(post("/api/v1/flags/{flagId}/strategies", flagId)
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void updateStrategy_shouldReturnUpdated() throws Exception {
        FlagStrategy strategy = new FlagStrategy();
        strategy.setFlagId(flagId);
        strategy.setEnvironmentId(environmentId);
        strategy.setEnabled(false);
        var saved = flagStrategyRepository.save(strategy);

        mockMvc.perform(put("/api/v1/flags/{flagId}/strategies/{id}", flagId, saved.getId())
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void deleteStrategy_shouldReturn204() throws Exception {
        FlagStrategy strategy = new FlagStrategy();
        strategy.setFlagId(flagId);
        strategy.setEnvironmentId(environmentId);
        strategy.setEnabled(true);
        var saved = flagStrategyRepository.save(strategy);

        mockMvc.perform(delete("/api/v1/flags/{flagId}/strategies/{id}", flagId, saved.getId())
                        .header("Authorization", auth()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteStrategy_shouldReturn404ForFlagInAnotherProject() throws Exception {
        ForeignStrategy foreign = createForeignStrategy("foreign-delete-strategy");

        mockMvc.perform(delete("/api/v1/flags/{flagId}/strategies/{id}", foreign.flagId(), foreign.strategyId())
                        .header("Authorization", auth()))
                .andExpect(status().isNotFound());

        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flag_strategies WHERE id = ?", Integer.class, foreign.strategyId()));
    }

    @Test
    void updateStrategy_shouldReturn404ForFlagInAnotherProject() throws Exception {
        ForeignStrategy foreign = createForeignStrategy("foreign-update-strategy");

        mockMvc.perform(put("/api/v1/flags/{flagId}/strategies/{id}", foreign.flagId(), foreign.strategyId())
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": true}"))
                .andExpect(status().isNotFound());

        assertEquals(false, jdbcTemplate.queryForObject(
            "SELECT enabled FROM flag_strategies WHERE id = ?", Boolean.class, foreign.strategyId()));
    }

    private record ForeignStrategy(Integer flagId, Integer strategyId) {}

    private ForeignStrategy createForeignStrategy(String key) {
        Project other = new Project();
        other.setName("Foreign Strategy Project");
        Integer otherProjectId = projectRepository.save(other).getId();

        Flag foreignFlag = new Flag();
        foreignFlag.setName(key);
        foreignFlag.setKey(key);
        foreignFlag.setProjectId(otherProjectId);
        foreignFlag.setFlagType(FlagType.RELEASE);
        Integer foreignFlagId = flagRepository.save(foreignFlag).getId();

        FlagStrategy foreignStrategy = new FlagStrategy();
        foreignStrategy.setFlagId(foreignFlagId);
        foreignStrategy.setEnvironmentId(environmentId);
        foreignStrategy.setEnabled(false);
        Integer foreignStrategyId = flagStrategyRepository.save(foreignStrategy).getId();

        return new ForeignStrategy(foreignFlagId, foreignStrategyId);
    }
}
