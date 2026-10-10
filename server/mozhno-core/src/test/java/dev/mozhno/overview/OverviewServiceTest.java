package dev.mozhno.overview;

import dev.mozhno.apikeys.ApiKeyService;
import dev.mozhno.audit.AuditService;
import dev.mozhno.auth.UserRepository;
import dev.mozhno.client.ClientInstanceRepository;
import dev.mozhno.environments.Environment;
import dev.mozhno.environments.EnvironmentService;
import dev.mozhno.flags.Flag;
import dev.mozhno.flags.FlagService;
import dev.mozhno.flags.FlagWithStrategy;
import dev.mozhno.flags.strategy.FlagStrategy;
import dev.mozhno.metrics.FlagMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OverviewServiceTest {

    @Mock
    private FlagService flagService;
    @Mock
    private EnvironmentService environmentService;
    @Mock
    private FlagMetricsService flagMetricsService;
    @Mock
    private ClientInstanceRepository clientInstanceRepository;
    @Mock
    private ApiKeyService apiKeyService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditService auditService;

    private OverviewService service;
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        service = new OverviewService(flagService, environmentService, flagMetricsService,
            clientInstanceRepository, apiKeyService, userRepository, auditService);
        lenient().when(flagMetricsService.getProjectMetrics(1, null)).thenReturn(List.of());
        lenient().when(clientInstanceRepository.findByProjectId(1)).thenReturn(List.of());
        lenient().when(apiKeyService.findByProjectId(1)).thenReturn(List.of());
        lenient().when(userRepository.countByProjectId(1)).thenReturn(1);
        lenient().when(auditService.findByProjectId(1, 0, 8, null, null)).thenReturn(List.of());
    }

    private OverviewData build(List<FlagWithStrategy> pairs) {
        Environment env = new Environment();
        env.setId(10);
        env.setName("prod");
        lenient().when(environmentService.findByProjectId(1)).thenReturn(List.of(env));
        lenient().when(flagService.findByProjectIdWithAllEnvironmentStrategies(1)).thenReturn(pairs);
        return service.build(1);
    }

    private static FlagWithStrategy pair(Flag flag, Instant lastUsedAt) {
        FlagStrategy s = new FlagStrategy();
        s.setEnvironmentId(10);
        s.setEnabled(false);
        s.setLastUsedAt(lastUsedAt);
        return new FlagWithStrategy(flag, s);
    }

    private static Flag flag(int id, Instant createdAt) {
        Flag f = new Flag();
        f.setId(id);
        f.setCreatedAt(createdAt);
        f.setArchived(false);
        f.setFlagType(null);
        return f;
    }

    @Test
    void freshFlagNeverEvaluatedIsNotStale() {
        Flag flag = flag(1, now.minus(1, ChronoUnit.DAYS));

        OverviewData data = build(List.of(pair(flag, null)));

        assertEquals(0, data.totals().staleFlags());
        assertEquals(0, data.environments().get(0).staleCount());
    }

    @Test
    void oldFlagNeverEvaluatedIsStale() {
        Flag flag = flag(1, now.minus(90, ChronoUnit.DAYS));

        OverviewData data = build(List.of(pair(flag, null)));

        assertEquals(1, data.totals().staleFlags());
        assertEquals(1, data.environments().get(0).staleCount());
    }

    @Test
    void oldFlagEvaluatedRecentlyIsNotStale() {
        Flag flag = flag(1, now.minus(90, ChronoUnit.DAYS));

        OverviewData data = build(List.of(pair(flag, now.minus(5, ChronoUnit.DAYS))));

        assertEquals(0, data.totals().staleFlags());
        assertEquals(0, data.environments().get(0).staleCount());
    }

    @Test
    void oldFlagEvaluatedLongAgoIsStale() {
        Flag flag = flag(1, now.minus(90, ChronoUnit.DAYS));

        OverviewData data = build(List.of(pair(flag, now.minus(40, ChronoUnit.DAYS))));

        assertEquals(1, data.totals().staleFlags());
        assertEquals(1, data.environments().get(0).staleCount());
    }

    @Test
    void flagWithoutCreatedAtFallsBackToOldBehavior() {
        Flag flag = flag(1, null);

        OverviewData data = build(List.of(pair(flag, null)));

        assertEquals(1, data.totals().staleFlags());
        assertEquals(1, data.environments().get(0).staleCount());
    }
}
