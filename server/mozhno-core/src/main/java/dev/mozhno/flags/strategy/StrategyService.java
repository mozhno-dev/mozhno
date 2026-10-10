package dev.mozhno.flags.strategy;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import dev.mozhno.events.DomainEvent;
import dev.mozhno.events.DomainEventPublisher;
import dev.mozhno.CacheNames;
import dev.mozhno.Operator;
import dev.mozhno.exception.BadRequestException;
import dev.mozhno.exception.NotFoundException;
import dev.mozhno.flags.Flag;
import dev.mozhno.flags.FlagRepository;
import dev.mozhno.client.FlagConstraintParser;
import dev.mozhno.contexts.ContextService;
import dev.mozhno.environments.EnvironmentRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for managing flag strategies, which control how a flag is evaluated per environment.
 * Strategies support percentage rollouts, context-based constraints, and segment targeting.
 */
@Service
public class StrategyService {
    private final FlagStrategyRepository strategyRepository;
    private final FlagRepository flagRepository;
    private final DomainEventPublisher events;
    private final ContextService contextService;
    private final EnvironmentRepository environmentRepository;

    public StrategyService(FlagStrategyRepository strategyRepository, FlagRepository flagRepository,
                           DomainEventPublisher events, ContextService contextService,
                           EnvironmentRepository environmentRepository) {
        this.strategyRepository = strategyRepository;
        this.flagRepository = flagRepository;
        this.events = events;
        this.contextService = contextService;
        this.environmentRepository = environmentRepository;
    }

    /**
     * Returns all strategies for a flag.
     *
     * @param flagId the flag ID
     * @return list of strategies
     * @throws RuntimeException if the flag is not found
     */
    @Transactional(readOnly = true)
    public List<FlagStrategy> findByFlagId(Integer flagId, Integer projectId) {
        if (flagRepository.findByIdAndProjectId(flagId, projectId) == null) {
            throw new NotFoundException("Flag", flagId);
        }
        return strategyRepository.findByFlagId(flagId);
    }

    @Transactional(readOnly = true)
    public List<FlagStrategy> findByFlagId(Integer flagId) {
        if (flagRepository.findById(flagId) == null) {
            throw new NotFoundException("Flag", flagId);
        }
        return strategyRepository.findByFlagId(flagId);
    }

    /**
     * Finds the strategy for a specific flag and environment combination.
     *
     * @param flagId the flag ID
     * @param environmentId the environment ID
     * @return the strategy, or null if none exists
     */
    @Transactional(readOnly = true)
    public FlagStrategy findByFlagIdAndEnvironmentId(Integer flagId, Integer environmentId) {
        return strategyRepository.findByFlagIdAndEnvironmentId(flagId, environmentId);
    }

    /**
     * Creates (or upserts) a strategy for a flag and environment.
     *
     * @param request the strategy creation request
     * @return the created strategy
     * @throws RuntimeException if the flag is not found
     */
    @Transactional
    public FlagStrategy create(StrategyRequest request, Integer projectId) {
        return upsert(request, projectId);
    }

    @Transactional
    public FlagStrategy create(StrategyRequest request) {
        return upsert(request);
    }

    /**
     * Deletes a strategy by its ID within the given flag and project.
     *
     * @param id the strategy ID
     * @param flagId the flag the strategy must belong to
     * @param projectId the owning project ID
     */
    @Transactional
    @CacheEvict(value = CacheNames.CLIENT_FLAGS, allEntries = true)
    public void delete(Integer id, Integer flagId, Integer projectId) {
        requireFlagInProject(flagId, projectId);
        FlagStrategy existing = strategyRepository.findByIdAndFlagId(id, flagId);
        if (existing == null) throw new NotFoundException("Strategy", id);
        strategyRepository.deleteById(id);
    }

    /**
     * Updates an existing strategy by its ID within the given flag and project.
     *
     * @param id the strategy ID
     * @param request the strategy update request
     * @param flagId the flag the strategy must belong to
     * @param projectId the owning project ID
     * @return the updated strategy
     * @throws RuntimeException if the flag or the strategy is not found
     */
    @Transactional
    @CacheEvict(value = CacheNames.CLIENT_FLAGS, allEntries = true)
    public FlagStrategy update(Integer id, StrategyRequest request, Integer flagId, Integer projectId) {
        requireFlagInProject(flagId, projectId);
        FlagStrategy existing = strategyRepository.findByIdAndFlagId(id, flagId);
        if (existing == null) throw new NotFoundException("Strategy", id);

        validateConstraintValues(request.getContextValuesJson());

        FlagStrategy saved = strategyRepository.updateById(
            id,
            request.getEnabled() != null ? request.getEnabled() : existing.isEnabled(),
            request.getPercentage(),
            request.getContextDefinitionId(),
            request.getContextValuesJson(),
            request.getSegmentIds()
        );
        if (saved == null) throw new NotFoundException("Strategy", id);

        var flag = flagRepository.findById(existing.getFlagId());
        if (flag != null) {
            var env = environmentRepository.findById(existing.getEnvironmentId());
            String envName = env != null ? env.getName() : String.valueOf(existing.getEnvironmentId());
            events.publish(DomainEvent.of(flag.getProjectId(), "strategy.updated", "strategy",
                saved.getId(), flag.getName(), "Environment: " + envName));
        }
        return saved;
    }

    private void requireFlagInProject(Integer flagId, Integer projectId) {
        if (flagRepository.findByIdAndProjectId(flagId, projectId) == null) {
            throw new NotFoundException("Flag", flagId);
        }
    }

    /**
     * Creates or updates a strategy for a flag and environment (upsert).
     *
     * @param request the strategy request
     * @return the upserted strategy
     * @throws RuntimeException if the flag is not found
     */
    @Transactional
    @CacheEvict(value = CacheNames.CLIENT_FLAGS, allEntries = true)
    public FlagStrategy upsert(StrategyRequest request, Integer projectId) {
        Flag flag;
        if (projectId != null) {
            flag = flagRepository.findByIdAndProjectId(request.getFlagId(), projectId);
        } else {
            flag = flagRepository.findById(request.getFlagId());
        }
        if (flag == null) {
            throw new NotFoundException("Flag", request.getFlagId());
        }

        validateConstraintValues(request.getContextValuesJson());

        boolean exists = strategyRepository.findByFlagIdAndEnvironmentId(
            request.getFlagId(), request.getEnvironmentId()) != null;

        FlagStrategy saved = strategyRepository.upsert(
            request.getFlagId(),
            request.getEnvironmentId(),
            request.getEnabled() != null ? request.getEnabled() : false,
            request.getPercentage(),
            request.getContextDefinitionId(),
            request.getContextValuesJson(),
            request.getSegmentIds()
        );

        var env = environmentRepository.findById(request.getEnvironmentId());
        String envName = env != null ? env.getName() : String.valueOf(request.getEnvironmentId());
        String action = exists ? "strategy.updated" : "strategy.created";
        events.publish(DomainEvent.of(flag.getProjectId(), action, "strategy",
            saved.getId(), flag.getName(), "Environment: " + envName));
        return saved;
    }

    @Transactional
    public FlagStrategy upsert(StrategyRequest request) {
        return upsert(request, null);
    }

    private void validateConstraintValues(String contextValuesJson) {
        if (contextValuesJson == null || contextValuesJson.isBlank()) return;
        List<FlagConstraintParser.StrategyConstraint> constraints =
            FlagConstraintParser.parseStrategyConstraints(contextValuesJson);
        Map<Integer, String> valuesByDefId = new HashMap<>();
        for (int i = 0; i < constraints.size(); i++) {
            FlagConstraintParser.StrategyConstraint c = constraints.get(i);
            String label = "Constraint #" + (i + 1);
            if (c.op() == null || c.op().isBlank()) {
                throw new BadRequestException(label + ": operator is required");
            }
            if (c.val() == null || c.val().isBlank()) {
                throw new BadRequestException(label + ": values are required");
            }
            if (!Operator.isMulti(c.op())) {
                String[] parts = c.val().split(",");
                long nonEmpty = java.util.Arrays.stream(parts)
                    .map(String::trim)
                    .filter(v -> !v.isEmpty())
                    .count();
                if (nonEmpty > 1) {
                    throw new BadRequestException(
                        label + ": single-value operator '" + c.op() +
                        "' cannot have multiple values (got " + nonEmpty + ")");
                }
            }
            if (c.cd() > 0 && !c.val().isBlank()) {
                valuesByDefId.merge(c.cd(), c.val(), (a, b) -> a + ", " + b);
            }
        }
        contextService.validateStrictValues(valuesByDefId);
    }
}