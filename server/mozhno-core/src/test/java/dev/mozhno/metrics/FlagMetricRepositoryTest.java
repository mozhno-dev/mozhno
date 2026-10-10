package dev.mozhno.metrics;

import dev.mozhno.BaseIntegrationTest;
import dev.mozhno.client.ClientInstanceRepository;
import dev.mozhno.environments.Environment;
import dev.mozhno.flags.Flag;
import dev.mozhno.flags.FlagType;
import dev.mozhno.projects.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlagMetricRepositoryTest extends BaseIntegrationTest {

    @Autowired
    private FlagMetricRepository repository;

    @Autowired
    private ClientInstanceRepository clientInstanceRepository;

    private Integer projectId;
    private Integer envId;

    @BeforeEach
    void setUp() {
        Project p = new Project();
        p.setName("Metrics Repo Project");
        projectId = projectRepository.save(p).getId();

        Environment env = new Environment();
        env.setName("production");
        env.setProjectId(projectId);
        envId = environmentRepository.save(env).getId();

        jdbcTemplate.execute("DELETE FROM flag_metrics");
        jdbcTemplate.execute("DELETE FROM client_instances");
    }

    private Flag createFlag(String key) {
        Flag flag = new Flag();
        flag.setProjectId(projectId);
        flag.setName(key);
        flag.setKey(key);
        flag.setFlagType(FlagType.RELEASE);
        return flagRepository.save(flag);
    }

    private Long createInstance(String appName, String instanceId) {
        return clientInstanceRepository.upsert(projectId, envId, null, appName, instanceId, "java", "2.4.0", "SERVER");
    }

    @Test
    void findContributors_empty_shouldReturnEmpty() {
        Flag flag = createFlag("empty-flag");
        List<FlagContributor> result = repository.findContributors(projectId, flag.getId(), envId, Instant.now().minus(48, ChronoUnit.HOURS));
        assertThat(result).isEmpty();
    }

    @Test
    void findContributors_shouldReturnInstanceWithSums() {
        Flag flag = createFlag("contrib-flag");
        Long instanceId = createInstance("web-app", "inst-a");

        repository.recordEvaluation(projectId, flag.getId(), envId, true, instanceId);
        repository.recordEvaluation(projectId, flag.getId(), envId, true, instanceId);
        repository.recordEvaluation(projectId, flag.getId(), envId, false, instanceId);

        List<FlagContributor> result = repository.findContributors(projectId, flag.getId(), envId, Instant.now().minus(48, ChronoUnit.HOURS));
        assertThat(result).hasSize(1);
        FlagContributor c = result.get(0);
        assertThat(c.instanceId()).isEqualTo(instanceId);
        assertThat(c.sdkInstanceId()).isEqualTo("inst-a");
        assertThat(c.appName()).isEqualTo("web-app");
        assertThat(c.appType()).isEqualTo("java");
        assertThat(c.sdkVersion()).isEqualTo("2.4.0");
        assertThat(c.lastSeenAt()).isNotNull();
        assertThat(c.evaluationTrueCount()).isEqualTo(2);
        assertThat(c.evaluationFalseCount()).isEqualTo(1);
        assertThat(c.totalEvaluations()).isEqualTo(3);
    }

    @Test
    void findContributors_shouldSumAcrossTimeBuckets() {
        Flag flag = createFlag("multi-bucket-flag");
        Long instanceId = createInstance("web-app", "inst-a");

        repository.recordEvaluation(projectId, flag.getId(), envId, true, instanceId);
        jdbcTemplate.update("""
            INSERT INTO flag_metrics (project_id, flag_id, environment_id, evaluation_true_count, evaluation_false_count, time_bucket, client_instance_id)
            VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL '3 hours', ?)
            """, projectId, flag.getId(), envId, 100, 50, instanceId);

        List<FlagContributor> result = repository.findContributors(projectId, flag.getId(), envId, Instant.now().minus(48, ChronoUnit.HOURS));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).evaluationTrueCount()).isEqualTo(101);
        assertThat(result.get(0).evaluationFalseCount()).isEqualTo(50);
    }

    @Test
    void findContributors_shouldFilterByFlagAndEnvironment() {
        Flag flagA = createFlag("flag-a");
        Flag flagB = createFlag("flag-b");
        Long instanceId = createInstance("web-app", "inst-a");

        Environment env2 = new Environment();
        env2.setName("staging");
        env2.setProjectId(projectId);
        Integer env2Id = environmentRepository.save(env2).getId();

        repository.recordEvaluation(projectId, flagA.getId(), envId, true, instanceId);
        repository.recordEvaluation(projectId, flagB.getId(), envId, true, instanceId);
        repository.recordEvaluation(projectId, flagA.getId(), env2Id, true, instanceId);

        List<FlagContributor> forFlagA = repository.findContributors(projectId, flagA.getId(), envId, Instant.now().minus(48, ChronoUnit.HOURS));
        assertThat(forFlagA).hasSize(1);
        assertThat(forFlagA.get(0).evaluationTrueCount()).isEqualTo(1);

        List<FlagContributor> forFlagB = repository.findContributors(projectId, flagB.getId(), envId, Instant.now().minus(48, ChronoUnit.HOURS));
        assertThat(forFlagB).hasSize(1);

        List<FlagContributor> forEnv2 = repository.findContributors(projectId, flagA.getId(), env2Id, Instant.now().minus(48, ChronoUnit.HOURS));
        assertThat(forEnv2).hasSize(1);
    }

    @Test
    void findContributors_shouldOrderByTotalContributionDescending() {
        Flag flag = createFlag("ordered-flag");
        Long top = createInstance("top-app", "inst-top");
        Long mid = createInstance("mid-app", "inst-mid");
        Long low = createInstance("low-app", "inst-low");

        repository.recordEvaluations(projectId, flag.getId(), envId, 100, 100, top);
        repository.recordEvaluations(projectId, flag.getId(), envId, 40, 40, mid);
        repository.recordEvaluations(projectId, flag.getId(), envId, 5, 0, low);

        List<FlagContributor> result = repository.findContributors(projectId, flag.getId(), envId, Instant.now().minus(48, ChronoUnit.HOURS));
        assertThat(result).hasSize(3);
        assertThat(result.get(0).sdkInstanceId()).isEqualTo("inst-top");
        assertThat(result.get(1).sdkInstanceId()).isEqualTo("inst-mid");
        assertThat(result.get(2).sdkInstanceId()).isEqualTo("inst-low");
    }

    @Test
    void findContributors_shouldIgnoreAggregatedRows() {
        Flag flag = createFlag("agg-flag");
        repository.recordEvaluation(projectId, flag.getId(), envId, true, null);

        List<FlagContributor> result = repository.findContributors(projectId, flag.getId(), envId, Instant.now().minus(48, ChronoUnit.HOURS));
        assertThat(result).isEmpty();
    }

    @Test
    void findContributors_shouldRespectSinceWindow() {
        Flag flag = createFlag("window-flag");
        Long instanceId = createInstance("web-app", "inst-a");

        repository.recordEvaluation(projectId, flag.getId(), envId, true, instanceId);
        jdbcTemplate.update("""
            INSERT INTO flag_metrics (project_id, flag_id, environment_id, evaluation_true_count, evaluation_false_count, time_bucket, client_instance_id)
            VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL '100 hours', ?)
            """, projectId, flag.getId(), envId, 100, 0, instanceId);

        List<FlagContributor> result = repository.findContributors(projectId, flag.getId(), envId, Instant.now().minus(48, ChronoUnit.HOURS));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).evaluationTrueCount()).isEqualTo(1);
    }

    @Test
    void findUsageByAppName_empty_shouldReturnEmpty() {
        createFlag("empty-usage-flag");

        List<FlagUsage> result = repository.findUsageByAppName(
            projectId, "web-app", envId, Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(result).isEmpty();
    }

    @Test
    void findUsageByAppName_shouldReturnFlagWithSums() {
        Flag flag = createFlag("usage-flag");
        Long instA = createInstance("web-app", "inst-a");
        Long instB = createInstance("web-app", "inst-b");

        repository.recordEvaluation(projectId, flag.getId(), envId, true, instA);
        repository.recordEvaluation(projectId, flag.getId(), envId, true, instB);
        repository.recordEvaluation(projectId, flag.getId(), envId, false, instB);

        List<FlagUsage> result = repository.findUsageByAppName(
            projectId, "web-app", envId, Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(result).hasSize(1);
        FlagUsage usage = result.get(0);
        assertThat(usage.flagId()).isEqualTo(flag.getId());
        assertThat(usage.key()).isEqualTo("usage-flag");
        assertThat(usage.name()).isEqualTo("usage-flag");
        assertThat(usage.flagType()).isEqualTo("RELEASE");
        assertThat(usage.evaluationTrueCount()).isEqualTo(2);
        assertThat(usage.evaluationFalseCount()).isEqualTo(1);
        assertThat(usage.totalEvaluations()).isEqualTo(3);
    }

    @Test
    void findUsageByAppName_shouldSumAcrossTimeBuckets() {
        Flag flag = createFlag("multi-bucket-usage-flag");
        Long instanceId = createInstance("web-app", "inst-a");

        repository.recordEvaluation(projectId, flag.getId(), envId, true, instanceId);
        jdbcTemplate.update("""
            INSERT INTO flag_metrics (project_id, flag_id, environment_id, evaluation_true_count, evaluation_false_count, time_bucket, client_instance_id)
            VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL '3 hours', ?)
            """, projectId, flag.getId(), envId, 100, 50, instanceId);

        List<FlagUsage> result = repository.findUsageByAppName(
            projectId, "web-app", envId, Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).evaluationTrueCount()).isEqualTo(101);
        assertThat(result.get(0).evaluationFalseCount()).isEqualTo(50);
        assertThat(result.get(0).totalEvaluations()).isEqualTo(151);
    }

    @Test
    void findUsageByAppName_shouldFilterByAppAndEnvironment() {
        Flag flag = createFlag("usage-filter-flag");
        Long webApp = createInstance("web-app", "inst-a");
        Long otherApp = createInstance("other-app", "inst-b");

        Environment env2 = new Environment();
        env2.setName("staging");
        env2.setProjectId(projectId);
        Integer env2Id = environmentRepository.save(env2).getId();

        repository.recordEvaluation(projectId, flag.getId(), envId, true, webApp);
        repository.recordEvaluation(projectId, flag.getId(), envId, true, otherApp);
        repository.recordEvaluation(projectId, flag.getId(), env2Id, true, webApp);

        List<FlagUsage> forWebApp = repository.findUsageByAppName(
            projectId, "web-app", envId, Instant.now().minus(7, ChronoUnit.DAYS));
        assertThat(forWebApp).hasSize(1);
        assertThat(forWebApp.get(0).evaluationTrueCount()).isEqualTo(1);

        List<FlagUsage> forOtherApp = repository.findUsageByAppName(
            projectId, "other-app", envId, Instant.now().minus(7, ChronoUnit.DAYS));
        assertThat(forOtherApp).hasSize(1);

        List<FlagUsage> forWebAppEnv2 = repository.findUsageByAppName(
            projectId, "web-app", env2Id, Instant.now().minus(7, ChronoUnit.DAYS));
        assertThat(forWebAppEnv2).hasSize(1);
    }

    @Test
    void findUsageByAppName_shouldIgnoreAggregatedRows() {
        Flag flag = createFlag("agg-usage-flag");
        repository.recordEvaluation(projectId, flag.getId(), envId, true, null);

        List<FlagUsage> result = repository.findUsageByAppName(
            projectId, "web-app", envId, Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(result).isEmpty();
    }

    @Test
    void findUsageByAppName_shouldExcludeArchivedFlags() {
        Flag flag = createFlag("archived-usage-flag");
        Long instanceId = createInstance("web-app", "inst-a");

        repository.recordEvaluation(projectId, flag.getId(), envId, true, instanceId);
        flagRepository.setArchived(flag.getId(), true, null, projectId);

        List<FlagUsage> result = repository.findUsageByAppName(
            projectId, "web-app", envId, Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(result).isEmpty();
    }

    @Test
    void findUsageByAppName_shouldOrderByTotalDescendingAndRespectWindow() {
        Flag top = createFlag("usage-top");
        Flag low = createFlag("usage-low");
        Long instanceId = createInstance("web-app", "inst-a");

        repository.recordEvaluations(projectId, top.getId(), envId, 100, 0, instanceId);
        repository.recordEvaluations(projectId, low.getId(), envId, 5, 0, instanceId);
        jdbcTemplate.update("""
            INSERT INTO flag_metrics (project_id, flag_id, environment_id, evaluation_true_count, evaluation_false_count, time_bucket, client_instance_id)
            VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL '200 hours', ?)
            """, projectId, top.getId(), envId, 1000, 0, instanceId);

        List<FlagUsage> result = repository.findUsageByAppName(
            projectId, "web-app", envId, Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).key()).isEqualTo("usage-top");
        assertThat(result.get(0).totalEvaluations()).isEqualTo(100);
        assertThat(result.get(1).key()).isEqualTo("usage-low");
    }

    @Test
    void findUsageByAppName_shouldExposeStrategyStateAndRoundedPercentage() {
        Flag flag = createFlag("strategy-usage-flag");
        Long instanceId = createInstance("web-app", "inst-a");
        jdbcTemplate.update("""
            INSERT INTO flag_strategies (flag_id, environment_id, enabled, percentage)
            VALUES (?, ?, TRUE, ?)
            """, flag.getId(), envId, 40.5);

        repository.recordEvaluation(projectId, flag.getId(), envId, true, instanceId);

        List<FlagUsage> result = repository.findUsageByAppName(
            projectId, "web-app", envId, Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).enabled()).isTrue();
        assertThat(result.get(0).percentage()).isEqualTo(41);
    }

    @Test
    void findUsageByAppName_shouldFallBackToFlagEnabledWithoutStrategy() {
        Flag flag = createFlag("no-strategy-usage-flag");
        Long instanceId = createInstance("web-app", "inst-a");

        repository.recordEvaluation(projectId, flag.getId(), envId, true, instanceId);

        List<FlagUsage> result = repository.findUsageByAppName(
            projectId, "web-app", envId, Instant.now().minus(7, ChronoUnit.DAYS));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).enabled()).isFalse();
        assertThat(result.get(0).percentage()).isNull();
    }

    @Test
    void deleteOlderThan_shouldRemoveOnlyExpiredBuckets() {
        Flag flag = createFlag("retention-flag");
        Long instanceId = createInstance("web-app", "inst-a");

        repository.recordEvaluation(projectId, flag.getId(), envId, true, instanceId);
        jdbcTemplate.update("""
            INSERT INTO flag_metrics (project_id, flag_id, environment_id, evaluation_true_count, evaluation_false_count, time_bucket, client_instance_id)
            VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL '100 days', ?)
            """, projectId, flag.getId(), envId, 50, 0, instanceId);
        jdbcTemplate.update("""
            INSERT INTO flag_metrics (project_id, flag_id, environment_id, evaluation_true_count, evaluation_false_count, time_bucket, client_instance_id)
            VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL '3 days', ?)
            """, projectId, flag.getId(), envId, 10, 0, instanceId);

        int deleted = repository.deleteOlderThan(30);

        assertThat(deleted).isEqualTo(1);
        Integer remaining = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flag_metrics WHERE flag_id = ?", Integer.class, flag.getId());
        assertThat(remaining).isEqualTo(2);
    }
}
