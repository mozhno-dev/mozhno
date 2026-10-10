package dev.mozhno.metrics;

import dev.mozhno.flags.FlagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import dev.mozhno.auth.UserPrincipal;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Metrics", description = "Feature flag evaluation metrics")
public class FlagMetricsController {
    private final FlagMetricsService flagMetricsService;
    private final FlagService flagService;
    private final MetricsAssembler metricsAssembler;

    @GetMapping("/api/v1/flags/{flagId}/metrics")
    @Operation(summary = "Get evaluation metrics for a specific flag")
    public List<FlagMetricResponse> getFlagMetrics(@PathVariable Integer flagId,
                                                   @RequestParam Integer environmentId,
                                                   @RequestParam(required = false) Long instanceId,
                                                   @RequestParam(required = false) String appName,
                                                   @AuthenticationPrincipal UserPrincipal user) {
        flagService.findById(flagId, user.projectId());
        List<FlagMetric> metrics;
        if (instanceId != null) {
            metrics = flagMetricsService.getMetricsByInstance(user.projectId(), flagId, environmentId, instanceId);
        } else if (appName != null && !appName.isEmpty()) {
            metrics = flagMetricsService.getMetricsByAppName(user.projectId(), flagId, environmentId, appName);
        } else {
            metrics = flagMetricsService.getMetrics(user.projectId(), flagId, environmentId);
        }
        return metricsAssembler.toResponseList(metrics);
    }

    @GetMapping("/api/v1/metrics")
    @Operation(summary = "Get evaluation metrics for all flags in a project")
    public List<FlagMetricResponse> getProjectMetrics(@RequestParam(required = false) Integer environmentId,
                                                      @AuthenticationPrincipal UserPrincipal user) {
        List<FlagMetric> metrics = flagMetricsService.getProjectMetrics(user.projectId(), environmentId);
        return metricsAssembler.toResponseList(metrics);
    }

    @GetMapping("/api/v1/flags/{flagId}/metrics/contributors")
    @Operation(summary = "Get per-instance flag contributors over the last 48 hours")
    public List<FlagContributorResponse> getContributors(@PathVariable Integer flagId,
                                                         @RequestParam Integer environmentId,
                                                         @AuthenticationPrincipal UserPrincipal user) {
        flagService.findById(flagId, user.projectId());
        List<FlagContributor> contributors = flagMetricsService.getContributors(
            user.projectId(), flagId, environmentId);
        return metricsAssembler.toContributorResponseList(contributors);
    }

    @GetMapping("/api/v1/projects/client-instances/usage")
    @Operation(summary = "Get flags a client application attempted to activate in an environment over a window")
    public ClientInstanceUsageResponse getAppFlagUsage(@RequestParam String appName,
                                                       @RequestParam Integer environmentId,
                                                       @RequestParam(required = false, defaultValue = "168") Integer hours,
                                                       @AuthenticationPrincipal UserPrincipal user) {
        int clampedHours = flagMetricsService.clampUsageWindow(hours);
        List<FlagUsage> usage = flagMetricsService.getUsageByAppName(
            user.projectId(), appName, environmentId, clampedHours);
        int totalActiveFlags = flagService.countActiveByProjectId(user.projectId());
        return ClientInstanceUsageResponse.builder()
            .appName(appName)
            .environmentId(environmentId)
            .hours(clampedHours)
            .totalActiveFlags(totalActiveFlags)
            .flags(metricsAssembler.toUsageResponseList(usage))
            .build();
    }
}
