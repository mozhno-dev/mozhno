package dev.mozhno.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import dev.mozhno.BaseIntegrationTest;
import dev.mozhno.environments.Environment;
import dev.mozhno.flags.Flag;
import dev.mozhno.flags.FlagType;
import dev.mozhno.metrics.FlagMetric;
import dev.mozhno.metrics.FlagMetricRepository;
import dev.mozhno.metrics.FlagMetricsService;
import dev.mozhno.metrics.MetricsProperties;
import dev.mozhno.projects.Project;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlagMetricsServiceTest extends BaseIntegrationTest {

    @Autowired
    private FlagMetricsService flagMetricsService;

    @Autowired
    private FlagMetricRepository flagMetricRepository;

    private Integer projectId;
    private Integer envId;

    @BeforeEach
    void setUp() {
        Project p = new Project();
        p.setName("Metrics Project");
        projectId = projectRepository.save(p).getId();

        Environment env = new Environment();
        env.setName("production");
        env.setProjectId(projectId);
        envId = environmentRepository.save(env).getId();
    }

    @Test
    void getMetrics_empty_shouldReturnEmpty() {
        List<FlagMetric> result = flagMetricsService.getMetrics(projectId, 1, envId);
        assertThat(result).isEmpty();
    }

    @Test
    void getProjectMetrics_empty_shouldReturnEmpty() {
        List<FlagMetric> result = flagMetricsService.getProjectMetrics(projectId, envId);
        assertThat(result).isEmpty();
    }

    @Test
    void getProjectMetrics_allEnvironments_shouldWork() {
        List<FlagMetric> result = flagMetricsService.getProjectMetrics(projectId, null);
        assertThat(result).isEmpty();
    }

    @Test
    void getMetrics_afterEvaluation_shouldReturnMetrics() {
        Flag flag = new Flag();
        flag.setProjectId(projectId);
        flag.setName("My Flag");
        flag.setKey("my-flag");
        flag.setFlagType(FlagType.RELEASE);
        Flag saved = flagRepository.save(flag);

        flagMetricRepository.recordEvaluation(projectId, saved.getId(), envId, true, null);
        flagMetricRepository.recordEvaluation(projectId, saved.getId(), envId, false, null);

        List<FlagMetric> result = flagMetricsService.getMetrics(projectId, saved.getId(), envId);
        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getEvaluationTrueCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.get(0).getEvaluationFalseCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void getMetrics_shouldNotReturnMetricsOfAnotherProject() {
        Flag flag = new Flag();
        flag.setProjectId(projectId);
        flag.setName("Isolated Flag");
        flag.setKey("isolated-flag");
        flag.setFlagType(FlagType.RELEASE);
        Flag saved = flagRepository.save(flag);

        flagMetricRepository.recordEvaluation(projectId, saved.getId(), envId, true, null);

        Project other = new Project();
        other.setName("Other Metrics Project");
        Integer otherProjectId = projectRepository.save(other).getId();

        List<FlagMetric> result = flagMetricsService.getMetrics(otherProjectId, saved.getId(), envId);
        assertThat(result).isEmpty();
    }

    @Test
    void clampUsageWindow_shouldClampToInstanceRetention() {
        assertThat(flagMetricsService.clampUsageWindow(168)).isEqualTo(168);
        assertThat(flagMetricsService.clampUsageWindow(720)).isEqualTo(720);
        assertThat(flagMetricsService.clampUsageWindow(10_000)).isEqualTo(720);
        assertThat(flagMetricsService.clampUsageWindow(0)).isEqualTo(1);
        assertThat(flagMetricsService.clampUsageWindow(-5)).isEqualTo(1);
    }

    @Test
    void clampUsageWindow_shouldRespectConfiguredRetentionAndStayOverflowSafe() {
        FlagMetricsService service = new FlagMetricsService(
            Mockito.mock(FlagMetricRepository.class),
            clientProperties(1000),
            metricsProperties(1000));
        assertThat(service.clampUsageWindow(10_000)).isEqualTo(365 * 24);

        FlagMetricsService sevenDays = new FlagMetricsService(
            Mockito.mock(FlagMetricRepository.class),
            clientProperties(30),
            metricsProperties(7));
        assertThat(sevenDays.clampUsageWindow(720)).isEqualTo(168);
        assertThat(sevenDays.clampUsageWindow(24)).isEqualTo(24);
    }

    private dev.mozhno.client.ClientProperties clientProperties(int retentionDays) {
        dev.mozhno.client.ClientProperties props = new dev.mozhno.client.ClientProperties();
        props.setInstanceRetentionDays(retentionDays);
        return props;
    }

    private MetricsProperties metricsProperties(int retentionDays) {
        MetricsProperties props = new MetricsProperties();
        props.setRetentionDays(retentionDays);
        return props;
    }

    @Test
    void purgeOldMetrics_shouldDeleteExpiredRows() {
        Flag flag = new Flag();
        flag.setProjectId(projectId);
        flag.setName("Retention Flag");
        flag.setKey("retention-flag");
        flag.setFlagType(FlagType.RELEASE);
        Flag saved = flagRepository.save(flag);

        flagMetricRepository.recordEvaluation(projectId, saved.getId(), envId, true, null);
        jdbcTemplate.update("""
            INSERT INTO flag_metrics (project_id, flag_id, environment_id, evaluation_true_count, evaluation_false_count, time_bucket)
            VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL '100 days')
            """, projectId, saved.getId(), envId, 100, 0);

        flagMetricsService.purgeOldMetrics();

        Integer remaining = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flag_metrics WHERE flag_id = ?", Integer.class, saved.getId());
        assertThat(remaining).isEqualTo(1);
    }
}
