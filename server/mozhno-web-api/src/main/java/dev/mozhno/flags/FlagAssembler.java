package dev.mozhno.flags;

import dev.mozhno.auth.User;
import dev.mozhno.auth.UserRepository;
import dev.mozhno.flags.strategy.FlagStrategy;
import dev.mozhno.tags.Tag;
import dev.mozhno.tags.TagRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class FlagAssembler {

    private final FlagTagValueRepository flagTagValueRepository;
    private final TagRepository tagRepository;
    private final UserRepository userRepository;

    public FlagAssembler(FlagTagValueRepository flagTagValueRepository,
                         TagRepository tagRepository,
                         UserRepository userRepository) {
        this.flagTagValueRepository = flagTagValueRepository;
        this.tagRepository = tagRepository;
        this.userRepository = userRepository;
    }

    public FlagResponse toResponse(Flag flag) {
        return toResponse(flag, null);
    }

    public FlagResponse toResponse(Flag flag, FlagStrategy strategy) {
        Set<Integer> userIds = new HashSet<>();
        if (flag.getCreatorId() != null) userIds.add(flag.getCreatorId());
        if (flag.getArchivedBy() != null) userIds.add(flag.getArchivedBy());
        Map<Integer, String> userNames = resolveUserNames(userIds);

        List<FlagResponse.TagValueResponse> tags = resolveTags(
            flagTagValueRepository.findByFlagId(flag.getId()), flag.getProjectId());
        String createdBy = userNames.get(flag.getCreatorId());
        String archivedBy = userNames.get(flag.getArchivedBy());

        return FlagResponse.builder()
            .id(flag.getId())
            .projectId(flag.getProjectId())
            .name(flag.getName())
            .key(flag.getKey())
            .description(flag.getDescription())
            .flagType(flag.getFlagType() != null ? flag.getFlagType().name() : "RELEASE")
            .createdAt(flag.getCreatedAt())
            .createdBy(createdBy)
            .lastUsedAt(strategy != null ? strategy.getLastUsedAt() : null)
            .archivedBy(archivedBy)
            .archivedAt(flag.getArchivedAt())
            .tags(tags)
            .enabled(strategy != null ? strategy.isEnabled() : flag.isEnabled())
            .strategyId(strategy != null ? strategy.getId() : null)
            .percentage(strategy != null ? strategy.getPercentage() : null)
            .contextDefinitionId(strategy != null ? strategy.getContextDefinitionId() : null)
            .contextValuesJson(strategy != null ? strategy.getContextValuesJson() : null)
            .segmentIds(strategy != null ? strategy.getSegmentIds() : null)
            .archived(flag.isArchived())
            .build();
    }

    public List<FlagResponse> toResponses(List<FlagWithStrategy> flagWithStrategies) {
        if (flagWithStrategies.isEmpty()) return List.of();

        Set<Integer> userIds = new HashSet<>();
        List<Integer> allFlagIds = new ArrayList<>();
        for (FlagWithStrategy fws : flagWithStrategies) {
            Flag flag = fws.flag();
            allFlagIds.add(flag.getId());
            if (flag.getCreatorId() != null) userIds.add(flag.getCreatorId());
            if (flag.getArchivedBy() != null) userIds.add(flag.getArchivedBy());
        }
        Map<Integer, String> userNameMap = resolveUserNames(userIds);
        Map<Integer, List<FlagTagValue>> tagValuesByFlag = flagTagValueRepository.findByFlagIds(allFlagIds);

        return flagWithStrategies.stream().map(fws -> {
            Flag flag = fws.flag();
            FlagStrategy strategy = fws.strategy();
            List<FlagResponse.TagValueResponse> tags = resolveTags(
                tagValuesByFlag.getOrDefault(flag.getId(), List.of()), flag.getProjectId());

            return FlagResponse.builder()
                .id(flag.getId())
                .projectId(flag.getProjectId())
                .name(flag.getName())
                .key(flag.getKey())
                .description(flag.getDescription())
                .flagType(flag.getFlagType() != null ? flag.getFlagType().name() : "RELEASE")
                .createdAt(flag.getCreatedAt())
                .createdBy(userNameMap.get(flag.getCreatorId()))
                .lastUsedAt(strategy != null ? strategy.getLastUsedAt() : null)
                .archivedBy(userNameMap.get(flag.getArchivedBy()))
                .archivedAt(flag.getArchivedAt())
                .tags(tags)
                .enabled(fws.isEnabled())
                .strategyId(strategy != null ? strategy.getId() : null)
                .percentage(strategy != null ? strategy.getPercentage() : null)
                .contextDefinitionId(strategy != null ? strategy.getContextDefinitionId() : null)
                .contextValuesJson(strategy != null ? strategy.getContextValuesJson() : null)
                .segmentIds(strategy != null ? strategy.getSegmentIds() : null)
                .archived(flag.isArchived())
                .build();
        }).toList();
    }

    public List<EnrichedFlagResponse> toEnrichedResponses(List<FlagWithStrategy> flagWithStrategies) {
        Map<Integer, List<FlagWithStrategy>> groupedByFlag = new LinkedHashMap<>();
        for (FlagWithStrategy fws : flagWithStrategies) {
            groupedByFlag.computeIfAbsent(fws.flag().getId(), k -> new ArrayList<>()).add(fws);
        }

        List<Integer> allFlagIds = new ArrayList<>(groupedByFlag.keySet());
        Map<Integer, List<FlagTagValue>> tagValuesByFlag = flagTagValueRepository.findByFlagIds(allFlagIds);

        Set<Integer> userIds = new HashSet<>();
        for (List<FlagWithStrategy> group : groupedByFlag.values()) {
            Flag first = group.get(0).flag();
            if (first.getCreatorId() != null) userIds.add(first.getCreatorId());
            if (first.getArchivedBy() != null) userIds.add(first.getArchivedBy());
        }
        Map<Integer, String> userNameMap = resolveUserNames(userIds);

        return groupedByFlag.values().stream().map(group -> {
            Flag first = group.get(0).flag();
            String createdBy = userNameMap.get(first.getCreatorId());
            String archivedBy = userNameMap.get(first.getArchivedBy());

            List<EnrichedFlagResponse.TagValueResponse> tags =
                resolveEnrichedTags(tagValuesByFlag.getOrDefault(first.getId(), List.of()), first.getProjectId());

            List<EnrichedFlagResponse.EnvironmentState> environments = group.stream()
                .filter(fws -> fws.strategy() != null)
                .map(fws -> {
                    FlagStrategy s = fws.strategy();
                    return EnrichedFlagResponse.EnvironmentState.builder()
                        .environmentId(s.getEnvironmentId())
                        .environmentName(s.getEnvironmentName() != null ? s.getEnvironmentName() : "Env " + s.getEnvironmentId())
                        .enabled(s.isEnabled())
                        .percentage(s.getPercentage())
                        .segmentIds(s.getSegmentIds())
                        .strategyId(s.getId())
                        .contextDefinitionId(s.getContextDefinitionId())
                        .contextValuesJson(s.getContextValuesJson())
                        .lastUsedAt(s.getLastUsedAt())
                        .build();
                })
                .toList();

            return EnrichedFlagResponse.builder()
                .id(first.getId())
                .projectId(first.getProjectId())
                .name(first.getName())
                .key(first.getKey())
                .description(first.getDescription())
                .flagType(first.getFlagType() != null ? first.getFlagType().name() : "RELEASE")
                .createdAt(first.getCreatedAt())
                .createdBy(createdBy)
                .lastUsedAt(environments.stream()
                    .map(EnrichedFlagResponse.EnvironmentState::lastUsedAt)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null))
                .archivedBy(archivedBy)
                .archivedAt(first.getArchivedAt())
                .tags(tags)
                .environments(environments)
                .archived(first.isArchived())
                .build();
        }).toList();
    }

    private List<FlagResponse.TagValueResponse> resolveTags(List<FlagTagValue> tagValues, Integer projectId) {
        List<Integer> tagIds = tagValues.stream().map(FlagTagValue::getTagId).distinct().toList();
        Map<Integer, Tag> tagMap = loadTagMap(tagIds, projectId);

        return tagValues.stream().map(ftv -> {
            Tag tag = tagMap.get(ftv.getTagId());
            return FlagResponse.TagValueResponse.builder()
                .tagId(ftv.getTagId())
                .tagName(tag != null ? tag.getName() : "")
                .tagColor(tag != null ? tag.getColor() : "")
                .value(ftv.getTagValue())
                .build();
        }).toList();
    }

    private List<EnrichedFlagResponse.TagValueResponse> resolveEnrichedTags(List<FlagTagValue> tagValues, Integer projectId) {
        List<Integer> tagIds = tagValues.stream().map(FlagTagValue::getTagId).distinct().toList();
        Map<Integer, Tag> tagMap = loadTagMap(tagIds, projectId);

        return tagValues.stream().map(ftv -> {
            Tag tag = tagMap.get(ftv.getTagId());
            return EnrichedFlagResponse.TagValueResponse.builder()
                .tagId(ftv.getTagId())
                .tagName(tag != null ? tag.getName() : "")
                .tagColor(tag != null ? tag.getColor() : "")
                .value(ftv.getTagValue())
                .build();
        }).toList();
    }

    private Map<Integer, Tag> loadTagMap(List<Integer> tagIds, Integer projectId) {
        if (tagIds.isEmpty()) return Collections.emptyMap();
        return tagRepository.findAllByIds(tagIds, projectId).stream()
            .collect(Collectors.toMap(Tag::getId, Function.identity()));
    }

    private Map<Integer, String> resolveUserNames(Set<Integer> userIds) {
        if (userIds.isEmpty()) return Collections.emptyMap();
        List<User> users = userRepository.findAllByIds(new ArrayList<>(userIds));
        Map<Integer, String> map = new LinkedHashMap<>();
        for (User user : users) {
            String name = user.getName() != null ? user.getName() : user.getEmail();
            map.put(user.getId(), name + " (" + user.getEmail() + ")");
        }
        return map;
    }

    private String resolveUserName(Integer userId) {
        if (userId == null) return null;
        return resolveUserNames(Set.of(userId)).get(userId);
    }
}
