package dev.mozhno.overview;

import dev.mozhno.apikeys.ApiKeyService;
import dev.mozhno.audit.AuditEvent;
import dev.mozhno.audit.AuditService;
import dev.mozhno.auth.UserRepository;
import dev.mozhno.client.ClientInstance;
import dev.mozhno.client.ClientInstanceRepository;
import dev.mozhno.environments.Environment;
import dev.mozhno.environments.EnvironmentService;
import dev.mozhno.flags.Flag;
import dev.mozhno.flags.FlagService;
import dev.mozhno.flags.FlagWithStrategy;
import dev.mozhno.flags.strategy.FlagStrategy;
import dev.mozhno.metrics.FlagMetric;
import dev.mozhno.metrics.FlagMetricsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregation service backing the home overview ({@code GET /api/v1/overview}).
 * Composes existing services/repositories — no new persistence concerns beyond simple counts.
 */
@Service
public class OverviewService {

    /** A flag is considered stale when it has not been evaluated within this window. */
    private static final int STALE_DAYS = 30;
    /** Number of recent audit events surfaced on the home feed. */
    private static final int RECENT_ACTIVITY_LIMIT = 8;

    private final FlagService flagService;
    private final EnvironmentService environmentService;
    private final FlagMetricsService flagMetricsService;
    private final ClientInstanceRepository clientInstanceRepository;
    private final ApiKeyService apiKeyService;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public OverviewService(FlagService flagService,
                           EnvironmentService environmentService,
                           FlagMetricsService flagMetricsService,
                           ClientInstanceRepository clientInstanceRepository,
                           ApiKeyService apiKeyService,
                           UserRepository userRepository,
                           AuditService auditService) {
        this.flagService = flagService;
        this.environmentService = environmentService;
        this.flagMetricsService = flagMetricsService;
        this.clientInstanceRepository = clientInstanceRepository;
        this.apiKeyService = apiKeyService;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public OverviewData build(Integer projectId) {
        Instant staleThreshold = Instant.now().minus(STALE_DAYS, ChronoUnit.DAYS);

        List<FlagWithStrategy> pairs = flagService.findByProjectIdWithAllEnvironmentStrategies(projectId);
        List<Environment> environments = environmentService.findByProjectId(projectId);
        List<FlagMetric> metrics = flagMetricsService.getProjectMetrics(projectId, null);
        List<ClientInstance> instances = clientInstanceRepository.findByProjectId(projectId);

        // Group strategy rows by flag (a flag appears once per environment).
        Map<Integer, List<FlagWithStrategy>> byFlag = new LinkedHashMap<>();
        for (FlagWithStrategy p : pairs) {
            byFlag.computeIfAbsent(p.flag().getId(), k -> new ArrayList<>()).add(p);
        }

        OverviewData.Totals totals = computeTotals(byFlag, staleThreshold);
        List<OverviewData.EnvironmentStat> envStats =
            computeEnvironmentStats(environments, byFlag, metrics, instances, staleThreshold);
        OverviewData.Onboarding onboarding =
            computeOnboarding(projectId, byFlag.isEmpty(), environments, instances);
        List<AuditEvent> recentActivity =
            auditService.findByProjectId(projectId, 0, RECENT_ACTIVITY_LIMIT, null, null);

        return new OverviewData(totals, envStats, onboarding, recentActivity);
    }

    private OverviewData.Totals computeTotals(Map<Integer, List<FlagWithStrategy>> byFlag,
                                              Instant staleThreshold) {
        int total = 0;
        int archived = 0;
        int stale = 0;
        int killswitches = 0;
        int rollouts = 0;

        for (List<FlagWithStrategy> group : byFlag.values()) {
            Flag flag = group.get(0).flag();
            if (flag.isArchived()) {
                archived++;
                continue;
            }
            total++;

            boolean enabledSomewhere = false;
            Instant lastUsed = null;
            for (FlagWithStrategy p : group) {
                FlagStrategy s = p.strategy();
                if (s == null) continue;
                if (s.isEnabled()) {
                    enabledSomewhere = true;
                    if (isRollout(s)) rollouts++;
                }
                if (s.getLastUsedAt() != null
                    && (lastUsed == null || s.getLastUsedAt().isAfter(lastUsed))) {
                    lastUsed = s.getLastUsedAt();
                }
            }

            if (isStale(flag.getCreatedAt(), lastUsed, staleThreshold)) stale++;
            if (enabledSomewhere && isKillswitch(flag)) killswitches++;
        }

        return new OverviewData.Totals(total, archived, stale, killswitches, rollouts);
    }

    private List<OverviewData.EnvironmentStat> computeEnvironmentStats(
            List<Environment> environments,
            Map<Integer, List<FlagWithStrategy>> byFlag,
            List<FlagMetric> metrics,
            List<ClientInstance> instances,
            Instant staleThreshold) {

        int totalActiveFlags = 0;
        for (List<FlagWithStrategy> group : byFlag.values()) {
            if (!group.get(0).flag().isArchived()) totalActiveFlags++;
        }

        Map<Integer, long[]> evalByEnv = new HashMap<>();
        for (FlagMetric m : metrics) {
            long[] acc = evalByEnv.computeIfAbsent(m.getEnvironmentId(), k -> new long[2]);
            acc[0] += m.getEvaluationTrueCount();
            acc[1] += m.getEvaluationFalseCount();
        }

        Map<Integer, int[]> appsByEnv = new HashMap<>();
        Map<Integer, Instant> lastSeenByEnv = new HashMap<>();
        for (ClientInstance ci : instances) {
            appsByEnv.computeIfAbsent(ci.getEnvironmentId(), k -> new int[1])[0]++;
            Instant seen = ci.getLastSeenAt();
            if (seen != null) {
                Instant cur = lastSeenByEnv.get(ci.getEnvironmentId());
                if (cur == null || seen.isAfter(cur)) lastSeenByEnv.put(ci.getEnvironmentId(), seen);
            }
        }

        List<OverviewData.EnvironmentStat> stats = new ArrayList<>(environments.size());
        for (Environment env : environments) {
            int envId = env.getId();
            int enabled = 0;
            int rollout = 0;
            int stale = 0;

            for (List<FlagWithStrategy> group : byFlag.values()) {
                Flag flag = group.get(0).flag();
                if (flag.isArchived()) continue;
                FlagStrategy s = strategyForEnv(group, envId);
                if (s == null) continue;
                if (s.isEnabled()) {
                    enabled++;
                    if (isRollout(s)) rollout++;
                }
                if (isStale(flag.getCreatedAt(), s.getLastUsedAt(), staleThreshold)) stale++;
            }

            long[] eval = evalByEnv.getOrDefault(envId, new long[2]);
            int apps = appsByEnv.getOrDefault(envId, new int[1])[0];
            Instant lastSeen = lastSeenByEnv.get(envId);

            stats.add(new OverviewData.EnvironmentStat(
                envId,
                env.getName(),
                totalActiveFlags,
                enabled,
                rollout,
                stale,
                eval[0],
                eval[1],
                apps,
                lastSeen
            ));
        }
        return stats;
    }

    private OverviewData.Onboarding computeOnboarding(Integer projectId,
                                                      boolean noFlags,
                                                      List<Environment> environments,
                                                      List<ClientInstance> instances) {
        boolean hasApiKey = !apiKeyService.findByProjectId(projectId).isEmpty();
        return new OverviewData.Onboarding(
            !noFlags,
            !environments.isEmpty(),
            hasApiKey,
            !instances.isEmpty(),
            userRepository.countByProjectId(projectId) > 1
        );
    }

    private static FlagStrategy strategyForEnv(List<FlagWithStrategy> group, int envId) {
        for (FlagWithStrategy p : group) {
            FlagStrategy s = p.strategy();
            if (s != null && s.getEnvironmentId() != null && s.getEnvironmentId() == envId) {
                return s;
            }
        }
        return null;
    }

    private static boolean isStale(Instant createdAt, Instant lastUsed, Instant staleThreshold) {
        if (createdAt != null && createdAt.isAfter(staleThreshold)) return false;
        return lastUsed == null || lastUsed.isBefore(staleThreshold);
    }

    private static boolean isRollout(FlagStrategy s) {
        Double pct = s.getPercentage();
        return pct != null && pct > 0.0 && pct < 100.0;
    }

    private static boolean isKillswitch(Flag flag) {
        return flag.getFlagType() != null && "KILLSWITCH".equals(flag.getFlagType().name());
    }
}
