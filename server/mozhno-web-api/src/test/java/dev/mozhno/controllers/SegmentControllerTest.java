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
import dev.mozhno.environments.Environment;
import dev.mozhno.flags.Flag;
import dev.mozhno.flags.FlagType;
import dev.mozhno.flags.strategy.FlagStrategy;
import dev.mozhno.projects.Project;
import dev.mozhno.segments.Segment;
import dev.mozhno.segments.SegmentRequest;

import java.util.List;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SegmentControllerTest extends BaseIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private Integer projectId;
    private Integer contextDefId;
    private String authToken;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
        objectMapper = new ObjectMapper();

        jdbcTemplate.update(
            "INSERT INTO users (email, password_hash, role, status) VALUES (?, ?, ?, ?)",
            "segment-test@test.com", passwordEncoder.encode("secret"), "developer", "active");

        Project p = new Project();
        p.setName("Test Project");
        projectId = projectRepository.save(p).getId();

        jdbcTemplate.update("UPDATE users SET project_id = ? WHERE email = ?", projectId, "segment-test@test.com");

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"segment-test@test.com\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        authToken = objectMapper.readTree(loginResponse).get("token").asText();

        ContextDefinition cd = new ContextDefinition();
        cd.setName("appName");
        cd.setProjectId(projectId);
        contextDefId = contextDefinitionRepository.save(cd).getId();
    }

    private String auth() {
        return "Bearer " + authToken;
    }

    @Test
    void getAllSegments_shouldReturnList() throws Exception {
        mockMvc.perform(get("/api/v1/segments")
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getAllSegments_shouldIncludeFlagUsageCount() throws Exception {
        Environment env = new Environment();
        env.setName("dev");
        env.setProjectId(projectId);
        Integer envId = environmentRepository.save(env).getId();

        Segment used = new Segment();
        used.setName("Used Segment");
        used.setProjectId(projectId);
        Segment saved = segmentRepository.save(used);

        Segment unused = new Segment();
        unused.setName("Unused Segment");
        unused.setProjectId(projectId);
        segmentRepository.save(unused);

        Flag f = new Flag();
        f.setProjectId(projectId);
        f.setName("flag with segment");
        f.setKey("flag-with-segment");
        f.setFlagType(FlagType.RELEASE);
        Integer flagId = flagRepository.save(f).getId();

        FlagStrategy fs = new FlagStrategy();
        fs.setFlagId(flagId);
        fs.setEnvironmentId(envId);
        fs.setEnabled(true);
        fs.setSegmentIds(List.of(saved.getId()));
        flagStrategyRepository.save(fs);

        mockMvc.perform(get("/api/v1/segments")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].usedByFlags", containsInAnyOrder(0, 1)));
    }

    @Test
    void createSegment_shouldReturnCreated() throws Exception {
        SegmentRequest req = new SegmentRequest();
        req.setName("Power Users");
        req.setDescription("High-value users");
        SegmentRequest.ContextEntry entry = new SegmentRequest.ContextEntry();
        entry.setContextDefinitionId(contextDefId);
        entry.setOperator("in");
        entry.setContextValues("[\"web\",\"mobile\"]");
        req.setContext(List.of(entry));

        mockMvc.perform(post("/api/v1/segments")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Power Users"))
                .andExpect(jsonPath("$.context").isArray())
                .andExpect(jsonPath("$.context[0].contextDefinitionId").value(contextDefId));
    }

    @Test
    void createSegment_withoutContext_shouldReturnBadRequest() throws Exception {
        SegmentRequest req = new SegmentRequest();
        req.setName("Empty Segment");
        req.setDescription("No context");
        req.setContext(List.of());

        mockMvc.perform(post("/api/v1/segments")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSegmentById_shouldReturnSegment() throws Exception {
        Segment s = new Segment();
        s.setName("Power Users");
        s.setProjectId(projectId);
        Segment saved = segmentRepository.save(s);

        mockMvc.perform(get("/api/v1/segments/{id}", saved.getId())
                .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Power Users"));
    }

    @Test
    void updateSegment_shouldReturnUpdated() throws Exception {
        Segment s = new Segment();
        s.setName("Old Name");
        s.setProjectId(projectId);
        Segment saved = segmentRepository.save(s);

        SegmentRequest req = new SegmentRequest();
        req.setName("Updated Name");
        req.setDescription("Updated description");
        SegmentRequest.ContextEntry entry = new SegmentRequest.ContextEntry();
        entry.setContextDefinitionId(contextDefId);
        entry.setOperator("in");
        entry.setContextValues("[\"mobile\"]");
        req.setContext(List.of(entry));

        mockMvc.perform(put("/api/v1/segments/{id}", saved.getId())
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.context[0].contextValues").value("[\"mobile\"]"));
    }

    @Test
    void updateSegment_shouldReplaceContext() throws Exception {
        Segment s = new Segment();
        s.setName("With Context");
        s.setProjectId(projectId);
        Segment saved = segmentRepository.save(s);

        SegmentRequest reqWithContext = new SegmentRequest();
        reqWithContext.setName("Replaced Context");
        reqWithContext.setDescription("Test");
        SegmentRequest.ContextEntry entry = new SegmentRequest.ContextEntry();
        entry.setContextDefinitionId(contextDefId);
        entry.setOperator("in");
        entry.setContextValues("[\"web\"]");
        reqWithContext.setContext(List.of(entry));

        mockMvc.perform(put("/api/v1/segments/{id}", saved.getId())
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqWithContext)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.length()").value(1));

        SegmentRequest reqEmpty = new SegmentRequest();
        reqEmpty.setName("Empty Context");
        reqEmpty.setDescription("Test");
        reqEmpty.setContext(List.of());

        mockMvc.perform(put("/api/v1/segments/{id}", saved.getId())
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqEmpty)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteSegment_shouldReturn204() throws Exception {
        Segment s = new Segment();
        s.setName("To Delete");
        s.setProjectId(projectId);
        Segment saved = segmentRepository.save(s);

        mockMvc.perform(delete("/api/v1/segments/{id}", saved.getId())
                .header("Authorization", auth()))
                .andExpect(status().isNoContent());
    }
}
