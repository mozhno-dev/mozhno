package dev.mozhno.flags;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import dev.mozhno.CacheNames;
import dev.mozhno.events.DomainEvent;
import dev.mozhno.events.DomainEventPublisher;
import dev.mozhno.exception.BadRequestException;
import dev.mozhno.exception.NotFoundException;
import dev.mozhno.common.PageResponse;
import dev.mozhno.spi.QuotaSpi;
import dev.mozhno.tags.Tag;
import dev.mozhno.tags.TagRepository;
import dev.mozhno.util.QuotaValidator;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for managing feature flags within a project.
 * Handles CRUD operations, archival, and quota enforcement for flags and their associated tags.
 */
@Service
public class FlagService {

    private final FlagRepository flagRepository;
    private final TagRepository tagRepository;
    private final FlagTagValueRepository flagTagValueRepository;
    private final DomainEventPublisher events;
    private final QuotaSpi quotaSpi;
    private final FlagsProperties flagsProperties;

    public FlagService(FlagRepository flagRepository,
                       TagRepository tagRepository, FlagTagValueRepository flagTagValueRepository,
                       DomainEventPublisher events, QuotaSpi quotaSpi,
                       FlagsProperties flagsProperties) {
        this.flagRepository = flagRepository;
        this.tagRepository = tagRepository;
        this.flagTagValueRepository = flagTagValueRepository;
        this.events = events;
        this.quotaSpi = quotaSpi;
        this.flagsProperties = flagsProperties;
    }

    /**
     * Finds a flag by its ID and verifies it belongs to the given project.
     *
     * @param id the flag ID
     * @param projectId the project ID to verify ownership
     * @return the flag
     * @throws RuntimeException if the flag is not found or belongs to another project
     */
    @Transactional(readOnly = true)
    public Flag findById(Integer id, Integer projectId) {
        Flag flag = flagRepository.findByIdAndProjectId(id, projectId);
        if (flag == null) throw new NotFoundException("Flag", id);
        return flag;
    }

    /**
     * Returns all non-archived flags belonging to the given project.
     *
     * @param projectId the project ID
     * @return list of flags
     * @throws RuntimeException if the project is not found
     */
    @Transactional(readOnly = true)
    public List<Flag> findByProjectId(Integer projectId) {
        return flagRepository.findByProjectId(projectId);
    }

    /**
     * Returns all flags (including archived) belonging to the given project.
     *
     * @param projectId the project ID
     * @return list of flags including archived ones
     */
    @Transactional(readOnly = true)
    public List<Flag> findByProjectIdIncludingArchived(Integer projectId) {
        return flagRepository.findByProjectIdIncludingArchived(projectId);
    }

    /**
     * Returns the number of non-archived flags in the given project.
     *
     * @param projectId the project ID
     * @return count of active flags
     */
    @Transactional(readOnly = true)
    public int countActiveByProjectId(Integer projectId) {
        return flagRepository.countActiveByProjectId(projectId);
    }

    /**
     * Finds a flag by its project and key.
     *
     * @param projectId the project ID
     * @param key the flag key
     * @return the flag
     * @throws RuntimeException if the project or flag is not found
     */
    @Transactional(readOnly = true)
    public Flag findByProjectIdAndKey(Integer projectId, String key) {
        Flag flag = flagRepository.findByProjectIdAndKey(projectId, key);
        if (flag == null) throw new NotFoundException("Flag", key);
        return flag;
    }

    /**
     * Returns all non-archived flags for a project with their strategy for the given environment.
     *
     * @param projectId the project ID
     * @param environmentId the environment ID
     * @return list of flags paired with their environment strategies
     */
    @Transactional(readOnly = true)
    public List<FlagWithStrategy> findByProjectIdWithStrategyForEnvironment(Integer projectId, Integer environmentId) {
        return flagRepository.findByProjectIdWithStrategyForEnvironment(projectId, environmentId);
    }

    /**
     * Returns all flags with their strategies for all environments.
     * A flag may appear multiple times in the result — once per environment strategy.
     *
     * @param projectId the project ID
     * @return list of flag+strategy pairs
     */
    @Transactional(readOnly = true)
    public List<FlagWithStrategy> findByProjectIdWithAllEnvironmentStrategies(Integer projectId) {
        return flagRepository.findByProjectIdWithAllEnvironmentStrategies(projectId);
    }


    @Transactional(readOnly = true)
    public PageResponse<FlagWithStrategy> findByProjectIdWithAllEnvironmentStrategiesPaginated(Integer projectId, int page, int size) {
        long total = flagRepository.countByProjectId(projectId, true);
        int offset = Math.multiplyExact(page, size);
        List<FlagWithStrategy> items = flagRepository.findByProjectIdWithAllEnvironmentStrategiesPaginated(projectId, offset, size);
        return new PageResponse<>(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public PageResponse<Flag> findByProjectIdPaginated(Integer projectId, boolean includeArchived, int page, int size) {
        long total = flagRepository.countByProjectId(projectId, includeArchived);
        int offset = Math.multiplyExact(page, size);
        List<Flag> items = flagRepository.findByProjectIdPaginated(projectId, includeArchived, offset, size);
        return new PageResponse<>(items, page, size, total);
    }

    /**
     * Creates a new flag.
     *
     * @param request the flag creation request
     * @param creatorId the ID of the user creating the flag, may be null
     * @return the created flag
     * @throws RuntimeException if the project is not found, quota is exceeded, or flag type is invalid
     */
    @Transactional
    @CacheEvict(value = CacheNames.CLIENT_FLAGS, allEntries = true)
    public Flag create(FlagRequest request, Integer creatorId) {
        QuotaValidator.check(quotaSpi.canCreateFlag(request.getProjectId()));

        Flag flag = new Flag();
        flag.setProjectId(request.getProjectId());
        flag.setName(request.getName());
        flag.setKey(request.getKey());
        flag.setDescription(request.getDescription());
        flag.setEnabled(request.getEnabled() != null ? request.getEnabled() : false);
        flag.setCreatorId(creatorId);
        if (request.getFlagType() != null) {
            try {
                flag.setFlagType(FlagType.valueOf(request.getFlagType().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid flag type: " + request.getFlagType()
                    + ". Must be one of: " + Arrays.toString(FlagType.values()));
            }
        }
        flag = flagRepository.save(flag);

        if (request.getTags() != null && !request.getTags().isEmpty()) {
            if (request.getTags().size() > flagsProperties.getMaxTagsPerFlag()) {
                throw new BadRequestException("At most " + flagsProperties.getMaxTagsPerFlag() + " tags per flag");
            }
            validateTagIds(request.getTags(), request.getProjectId());
            flagTagValueRepository.saveBatch(flag.getId(), request.getTags());
        }

        events.publish(DomainEvent.of(flag.getProjectId(), "flag.created", "flag",
            flag.getId(), flag.getName(), "Key: " + flag.getKey()));
        return flag;
    }

    /**
     * Updates an existing flag and replaces its tag assignments.
     *
     * @param id the flag ID
     * @param request the flag update request
     * @return the updated flag
     * @throws RuntimeException if the flag or a tag is not found, or flag type is invalid
     */
    @Transactional
    @CacheEvict(value = CacheNames.CLIENT_FLAGS, allEntries = true)
    public Flag update(Integer id, FlagRequest request) {
        Flag flag = flagRepository.findByIdAndProjectId(id, request.getProjectId());
        if (flag == null) throw new NotFoundException("Flag", id);
        flag.setName(request.getName());
        flag.setKey(request.getKey());
        flag.setDescription(request.getDescription());
        if (request.getEnabled() != null) {
            flag.setEnabled(request.getEnabled());
        }
        if (request.getFlagType() != null) {
            try {
                flag.setFlagType(FlagType.valueOf(request.getFlagType().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid flag type: " + request.getFlagType()
                    + ". Must be one of: " + Arrays.toString(FlagType.values()));
            }
        }
        flag = flagRepository.save(flag);

        flagTagValueRepository.deleteByFlagId(id);

        if (request.getTags() != null && !request.getTags().isEmpty()) {
            if (request.getTags().size() > flagsProperties.getMaxTagsPerFlag()) {
                throw new BadRequestException("At most " + flagsProperties.getMaxTagsPerFlag() + " tags per flag");
            }
            validateTagIds(request.getTags(), request.getProjectId());
            flagTagValueRepository.saveBatch(id, request.getTags());
        }

        events.publish(DomainEvent.of(flag.getProjectId(), "flag.updated", "flag",
            flag.getId(), flag.getName(), "Key: " + flag.getKey()));
        return flag;
    }

    /**
     * Deletes a flag and its associated tag values.
     *
     * @param id the flag ID
     */
    @Transactional
    @CacheEvict(value = CacheNames.CLIENT_FLAGS, allEntries = true)
    public void delete(Integer id, Integer projectId) {
        Flag flag = flagRepository.findByIdAndProjectId(id, projectId);
        if (flag == null) throw new NotFoundException("Flag", id);
        flagTagValueRepository.deleteByFlagId(id);
        int deleted = flagRepository.deleteById(id, projectId);
        if (deleted == 0) throw new NotFoundException("Flag", id);
        events.publish(DomainEvent.of(projectId, "flag.deleted", "flag",
            id, flag.getName(), "Flag deleted"));
    }

    @Transactional
    @CacheEvict(value = CacheNames.CLIENT_FLAGS, allEntries = true)
    public Flag archive(Integer id, Integer archivedBy, Integer projectId) {
        int affected = flagRepository.setArchived(id, true, archivedBy, projectId);
        if (affected == 0) throw new NotFoundException("Flag", id);
        Flag flag = flagRepository.findByIdAndProjectId(id, projectId);
        events.publish(DomainEvent.of(projectId, "flag.archived", "flag",
            id, flag.getName(), "Flag archived"));
        return flag;
    }

    @Transactional
    @CacheEvict(value = CacheNames.CLIENT_FLAGS, allEntries = true)
    public Flag unarchive(Integer id, Integer projectId) {
        int affected = flagRepository.clearArchived(id, projectId);
        if (affected == 0) throw new NotFoundException("Flag", id);
        Flag flag = flagRepository.findByIdAndProjectId(id, projectId);
        events.publish(DomainEvent.of(projectId, "flag.unarchived", "flag",
            id, flag.getName(), "Flag unarchived"));
        return flag;
    }

    private void validateTagIds(List<FlagRequest.TagValue> tags, Integer projectId) {
        List<Integer> tagIds = tags.stream().map(FlagRequest.TagValue::getTagId).distinct().toList();
        List<Tag> found = tagRepository.findAllByIds(tagIds, projectId);
        if (found.size() != tagIds.size()) {
            Set<Integer> foundIds = found.stream().map(Tag::getId).collect(Collectors.toSet());
            for (Integer tagId : tagIds) {
                if (!foundIds.contains(tagId)) {
                    throw new NotFoundException("Tag", tagId);
                }
            }
        }
    }
}
