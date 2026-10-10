package dev.mozhno.metrics;

import dev.mozhno.client.ClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Service for retrieving flag evaluation metrics.
 * Read queries default to the last 48 hours; the per-app usage window is additionally
 * clamped by the client instance and metric retention periods, since per-app attribution
 * depends on the client_instances rows that the retention job purges.
 * Includes a scheduled job to purge metric rows older than the configured retention period.
 */
@Service
public class FlagMetricsService {
    private static final Logger log = LoggerFactory.getLogger(FlagMetricsService.class);

    private final FlagMetricRepository flagMetricRepository;
    private final ClientProperties clientProperties;
    private final MetricsProperties metricsProperties;

    public FlagMetricsService(FlagMetricRepository flagMetricRepository,
                              ClientProperties clientProperties,
                              MetricsProperties metricsProperties) {
        this.flagMetricRepository = flagMetricRepository;
        this.clientProperties = clientProperties;
        this.metricsProperties = metricsProperties;
    }

    /**
     * Returns aggregated metrics for a specific flag and environment over the last 48 hours.
     *
     * @param projectId the owning project ID
     * @param flagId the flag ID
     * @param environmentId the environment ID
     * @return list of hourly metrics
     */
    public List<FlagMetric> getMetrics(Integer projectId, Integer flagId, Integer environmentId) {
        Instant since = Instant.now().minus(48, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        return flagMetricRepository.findByFlagIdAndEnvironmentId(projectId, flagId, environmentId, since);
    }

    /**
     * Returns per-instance metrics for a specific flag, environment, and client instance.
     */
    public List<FlagMetric> getMetricsByInstance(Integer projectId, Integer flagId, Integer environmentId, Long instanceId) {
        Instant since = Instant.now().minus(48, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        return flagMetricRepository.findByFlagIdAndEnvironmentIdAndInstanceId(projectId, flagId, environmentId, instanceId, since);
    }

    /**
     * Returns per-app metrics for a specific flag and environment, filtered by app_name.
     */
    public List<FlagMetric> getMetricsByAppName(Integer projectId, Integer flagId, Integer environmentId, String appName) {
        Instant since = Instant.now().minus(48, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        return flagMetricRepository.findByFlagIdAndEnvironmentIdAndAppName(projectId, flagId, environmentId, appName, since);
    }

    /**
     * Returns per-instance contributor summaries for a specific flag and environment over the last 48 hours.
     *
     * @param projectId the owning project ID
     * @param flagId the flag ID
     * @param environmentId the environment ID
     * @return list of contributors ordered by total contribution descending
     */
    public List<FlagContributor> getContributors(Integer projectId, Integer flagId, Integer environmentId) {
        Instant since = Instant.now().minus(48, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        return flagMetricRepository.findContributors(projectId, flagId, environmentId, since);
    }

    /**
     * Clamps a requested usage window (in hours) to the available retention, since per-app
     * attribution is only available while both the metric rows and the instance rows are retained.
     * Retention values are capped at one year to keep the window calculation overflow-safe.
     *
     * @param hours the requested window in hours
     * @return the clamped window in hours, within [1, min(instanceRetentionDays, metricsRetentionDays, 365) * 24]
     */
    public int clampUsageWindow(int hours) {
        int retentionDays = Math.min(
            Math.min(clientProperties.getInstanceRetentionDays(), 365),
            Math.min(metricsProperties.getRetentionDays(), 365));
        int maxHours = retentionDays * 24;
        return Math.clamp(hours, 1, maxHours);
    }

    /**
     * Scheduled task that purges flag metric rows older than the configured retention period.
     * Runs daily at 3:15 AM, between the audit (3:00) and client instance (3:30) purges.
     */
    @Scheduled(cron = "0 15 3 * * ?")
    @Transactional
    public void purgeOldMetrics() {
        int retentionDays = metricsProperties.getRetentionDays();
        int deleted = flagMetricRepository.deleteOlderThan(retentionDays);
        if (deleted > 0) {
            log.info("Purged {} flag metric rows older than {} days", deleted, retentionDays);
        }
    }

    /**
     * Returns per-app flag usage summaries for an environment over the given window.
     * The window must already be clamped with {@link #clampUsageWindow(int)}, since per-app
     * attribution is only available while both the metric rows and the instance rows are retained.
     *
     * @param projectId the project ID
     * @param appName the application name
     * @param environmentId the environment ID
     * @param hours the clamped window in hours
     * @return list of flag usage summaries ordered by total evaluations descending
     */
    public List<FlagUsage> getUsageByAppName(Integer projectId, String appName, Integer environmentId, int hours) {
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        return flagMetricRepository.findUsageByAppName(projectId, appName, environmentId, since);
    }

    /**
     * Returns metrics for all flags in a project, optionally filtered by environment, over the last 48 hours.
     *
     * @param projectId the project ID
     * @param environmentId the environment ID, may be null for all environments
     * @return list of hourly metrics
     */
    public List<FlagMetric> getProjectMetrics(Integer projectId, Integer environmentId) {
        Instant since = Instant.now().minus(48, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        if (environmentId != null) {
            return flagMetricRepository.findByProjectIdAndEnvironmentId(projectId, environmentId, since);
        }
        return flagMetricRepository.findByProjectId(projectId, since);
    }
}
