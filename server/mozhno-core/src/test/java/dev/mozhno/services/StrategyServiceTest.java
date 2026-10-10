package dev.mozhno.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import dev.mozhno.events.DomainEventPublisher;
import dev.mozhno.flags.Flag;
import dev.mozhno.flags.FlagRepository;
import dev.mozhno.flags.strategy.*;
import dev.mozhno.contexts.ContextService;
import dev.mozhno.environments.Environment;
import dev.mozhno.environments.EnvironmentRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StrategyServiceTest {

    @Mock
    private FlagStrategyRepository strategyRepository;

    @Mock
    private FlagRepository flagRepository;

    @Mock
    private DomainEventPublisher events;

    @Mock
    private ContextService contextService;

    @Mock
    private EnvironmentRepository environmentRepository;

    private StrategyService strategyService;

    @BeforeEach
    void setUp() {
        strategyService = new StrategyService(strategyRepository, flagRepository, events, contextService, environmentRepository);
    }

    @Test
    void findByFlagId_shouldReturnStrategies() {
        Flag flag = new Flag();
        flag.setId(1);
        when(flagRepository.findById(1)).thenReturn(flag);
        FlagStrategy s = new FlagStrategy();
        s.setId(1);
        when(strategyRepository.findByFlagId(1)).thenReturn(List.of(s));

        List<FlagStrategy> result = strategyService.findByFlagId(1);
        assertEquals(1, result.size());
    }

    @Test
    void findByFlagId_shouldThrowExceptionWhenFlagNotFound() {
        when(flagRepository.findById(999)).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> strategyService.findByFlagId(999));
        assertTrue(ex.getMessage().contains("Flag not found"));
    }

    @Test
    void create_shouldCreateStrategy() {
        Flag flag = new Flag();
        flag.setId(1);
        flag.setProjectId(100);
        flag.setName("test-flag");
        when(flagRepository.findById(1)).thenReturn(flag);

        Environment env = new Environment();
        env.setId(2);
        env.setName("Production");
        when(environmentRepository.findById(2)).thenReturn(env);
        when(strategyRepository.findByFlagIdAndEnvironmentId(1, 2)).thenReturn(null);

        FlagStrategy mockSaved = new FlagStrategy();
        mockSaved.setId(10);
        mockSaved.setFlagId(1);
        mockSaved.setEnvironmentId(2);
        mockSaved.setEnabled(true);
        mockSaved.setPercentage(50.0);
        mockSaved.setSegmentIds(List.of(5));
        when(strategyRepository.upsert(1, 2, true, 50.0, null, null, List.of(5))).thenReturn(mockSaved);

        StrategyRequest req = new StrategyRequest();
        req.setFlagId(1);
        req.setEnvironmentId(2);
        req.setEnabled(true);
        req.setPercentage(50.0);
        req.setSegmentIds(List.of(5));

        FlagStrategy result = strategyService.create(req);
        assertTrue(result.isEnabled());
        assertEquals(50.0, result.getPercentage());
        assertEquals(List.of(5), result.getSegmentIds());
        verify(strategyRepository).upsert(1, 2, true, 50.0, null, null, List.of(5));
    }

    @Test
    void create_shouldCreateStrategyWithContext() {
        Flag flag = new Flag();
        flag.setId(1);
        flag.setProjectId(100);
        flag.setName("test-flag");
        when(flagRepository.findById(1)).thenReturn(flag);

        Environment env = new Environment();
        env.setId(2);
        env.setName("Development");
        when(environmentRepository.findById(2)).thenReturn(env);
        when(strategyRepository.findByFlagIdAndEnvironmentId(1, 2)).thenReturn(null);

        FlagStrategy mockSaved = new FlagStrategy();
        mockSaved.setId(10);
        mockSaved.setFlagId(1);
        mockSaved.setEnvironmentId(2);
        mockSaved.setEnabled(true);
        mockSaved.setPercentage(30.0);
        mockSaved.setContextDefinitionId(3);
        mockSaved.setContextValuesJson("[\"web\"]");
        when(strategyRepository.upsert(1, 2, true, 30.0, 3, "[\"web\"]", null)).thenReturn(mockSaved);

        StrategyRequest req = new StrategyRequest();
        req.setFlagId(1);
        req.setEnvironmentId(2);
        req.setEnabled(true);
        req.setPercentage(30.0);
        req.setContextDefinitionId(3);
        req.setContextValuesJson("[\"web\"]");

        FlagStrategy result = strategyService.create(req);
        assertTrue(result.isEnabled());
        assertEquals(30.0, result.getPercentage());
        assertEquals(3, result.getContextDefinitionId());
        assertEquals("[\"web\"]", result.getContextValuesJson());
        verify(strategyRepository).upsert(1, 2, true, 30.0, 3, "[\"web\"]", null);
    }

    @Test
    void create_shouldThrowExceptionWhenFlagNotFound() {
        when(flagRepository.findById(999)).thenReturn(null);

        StrategyRequest req = new StrategyRequest();
        req.setFlagId(999);
        req.setEnvironmentId(2);
        req.setEnabled(true);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> strategyService.create(req));
        assertTrue(ex.getMessage().contains("Flag not found"));
    }

    @Test
    void update_shouldUpdateStrategy() {
        Flag flag = new Flag();
        flag.setId(10);
        flag.setProjectId(100);
        when(flagRepository.findByIdAndProjectId(10, 100)).thenReturn(flag);

        FlagStrategy existing = new FlagStrategy();
        existing.setId(1);
        existing.setFlagId(10);
        existing.setEnabled(false);
        when(strategyRepository.findByIdAndFlagId(1, 10)).thenReturn(existing);

        FlagStrategy updated = new FlagStrategy();
        updated.setId(1);
        updated.setEnabled(true);
        when(strategyRepository.updateById(1, true, null, null, null, null)).thenReturn(updated);

        StrategyRequest req = new StrategyRequest();
        req.setEnabled(true);

        FlagStrategy result = strategyService.update(1, req, 10, 100);
        assertTrue(result.isEnabled());
        verify(strategyRepository).updateById(1, true, null, null, null, null);
    }

    @Test
    void update_shouldThrowExceptionWhenNotFound() {
        Flag flag = new Flag();
        flag.setId(10);
        flag.setProjectId(100);
        when(flagRepository.findByIdAndProjectId(10, 100)).thenReturn(flag);
        when(strategyRepository.findByIdAndFlagId(999, 10)).thenReturn(null);

        StrategyRequest req = new StrategyRequest();
        req.setEnabled(true);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> strategyService.update(999, req, 10, 100));
        assertTrue(ex.getMessage().contains("Strategy not found"));
    }

    @Test
    void update_shouldThrowWhenFlagBelongsToAnotherProject() {
        when(flagRepository.findByIdAndProjectId(10, 100)).thenReturn(null);

        StrategyRequest req = new StrategyRequest();
        req.setEnabled(true);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> strategyService.update(1, req, 10, 100));
        assertTrue(ex.getMessage().contains("Flag not found"));
        verify(strategyRepository, never()).updateById(anyInt(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void update_shouldUpdatePercentage() {
        Flag flag = new Flag();
        flag.setId(10);
        flag.setProjectId(100);
        when(flagRepository.findByIdAndProjectId(10, 100)).thenReturn(flag);

        FlagStrategy existing = new FlagStrategy();
        existing.setId(1);
        existing.setPercentage(10.0);
        when(strategyRepository.findByIdAndFlagId(1, 10)).thenReturn(existing);

        FlagStrategy updated = new FlagStrategy();
        updated.setId(1);
        updated.setEnabled(true);
        updated.setPercentage(75.0);
        when(strategyRepository.updateById(1, true, 75.0, null, null, null)).thenReturn(updated);

        StrategyRequest req = new StrategyRequest();
        req.setEnabled(true);
        req.setPercentage(75.0);

        FlagStrategy result = strategyService.update(1, req, 10, 100);
        assertEquals(75.0, result.getPercentage());
    }

    @Test
    void update_shouldUpdateContextFields() {
        Flag flag = new Flag();
        flag.setId(10);
        flag.setProjectId(100);
        when(flagRepository.findByIdAndProjectId(10, 100)).thenReturn(flag);

        FlagStrategy existing = new FlagStrategy();
        existing.setId(1);
        when(strategyRepository.findByIdAndFlagId(1, 10)).thenReturn(existing);

        FlagStrategy updated = new FlagStrategy();
        updated.setId(1);
        updated.setEnabled(true);
        updated.setContextDefinitionId(5);
        updated.setContextValuesJson("[\"mobile\"]");
        updated.setSegmentIds(List.of(7));
        when(strategyRepository.updateById(1, true, null, 5, "[\"mobile\"]", List.of(7))).thenReturn(updated);

        StrategyRequest req = new StrategyRequest();
        req.setEnabled(true);
        req.setContextDefinitionId(5);
        req.setContextValuesJson("[\"mobile\"]");
        req.setSegmentIds(List.of(7));

        FlagStrategy result = strategyService.update(1, req, 10, 100);
        assertEquals(5, result.getContextDefinitionId());
        assertEquals("[\"mobile\"]", result.getContextValuesJson());
        assertEquals(List.of(7), result.getSegmentIds());
        verify(strategyRepository).updateById(1, true, null, 5, "[\"mobile\"]", List.of(7));
    }

    @Test
    void upsert_shouldCreateWhenNotExists() {
        Flag flag = new Flag();
        flag.setId(1);
        flag.setProjectId(100);
        flag.setName("test-flag");
        when(flagRepository.findById(1)).thenReturn(flag);

        Environment env = new Environment();
        env.setId(2);
        env.setName("Production");
        when(environmentRepository.findById(2)).thenReturn(env);
        when(strategyRepository.findByFlagIdAndEnvironmentId(1, 2)).thenReturn(null);

        FlagStrategy mockSaved = new FlagStrategy();
        mockSaved.setId(10);
        mockSaved.setFlagId(1);
        mockSaved.setEnvironmentId(2);
        mockSaved.setEnabled(true);
        when(strategyRepository.upsert(1, 2, true, null, null, null, null)).thenReturn(mockSaved);

        StrategyRequest req = new StrategyRequest();
        req.setFlagId(1);
        req.setEnvironmentId(2);
        req.setEnabled(true);

        FlagStrategy result = strategyService.upsert(req);
        assertTrue(result.isEnabled());
        verify(strategyRepository).upsert(1, 2, true, null, null, null, null);
    }

    @Test
    void upsert_shouldUpdateWhenExists() {
        Flag flag = new Flag();
        flag.setId(1);
        flag.setProjectId(100);
        flag.setName("test-flag");
        when(flagRepository.findById(1)).thenReturn(flag);

        Environment env = new Environment();
        env.setId(2);
        env.setName("Production");
        when(environmentRepository.findById(2)).thenReturn(env);
        when(strategyRepository.findByFlagIdAndEnvironmentId(1, 2)).thenReturn(new FlagStrategy());

        FlagStrategy updated = new FlagStrategy();
        updated.setId(5);
        updated.setEnabled(true);
        when(strategyRepository.upsert(1, 2, true, null, null, null, null)).thenReturn(updated);

        StrategyRequest req = new StrategyRequest();
        req.setFlagId(1);
        req.setEnvironmentId(2);
        req.setEnabled(true);

        FlagStrategy result = strategyService.upsert(req);
        assertTrue(result.isEnabled());
        verify(strategyRepository).upsert(1, 2, true, null, null, null, null);
    }

    @Test
    void delete_shouldCallRepository() {
        Flag flag = new Flag();
        flag.setId(10);
        flag.setProjectId(100);
        when(flagRepository.findByIdAndProjectId(10, 100)).thenReturn(flag);

        FlagStrategy existing = new FlagStrategy();
        existing.setId(1);
        when(strategyRepository.findByIdAndFlagId(1, 10)).thenReturn(existing);
        doNothing().when(strategyRepository).deleteById(1);
        strategyService.delete(1, 10, 100);
        verify(strategyRepository).deleteById(1);
    }

    @Test
    void delete_shouldThrowWhenFlagBelongsToAnotherProject() {
        when(flagRepository.findByIdAndProjectId(10, 100)).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> strategyService.delete(1, 10, 100));
        assertTrue(ex.getMessage().contains("Flag not found"));
        verify(strategyRepository, never()).deleteById(anyInt());
    }
}