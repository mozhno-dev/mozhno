package dev.mozhno.flags.strategy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import dev.mozhno.auth.UserPrincipal;

import java.util.List;

/**
 * REST controller for managing rollout strategies on a feature flag.
 * Strategies define how a flag is rolled out—using percentage, context-based
 * targeting, or segment-based rules per environment.
 *
 * @see StrategyService
 */
@RestController
@RequestMapping("/api/v1/flags/{flagId}/strategies")
@RequiredArgsConstructor
@Tag(name = "Strategies", description = "Flag strategy management")
public class StrategyController {
    private final StrategyService strategyService;
    private final StrategyAssembler strategyAssembler;

    @GetMapping
    @Operation(summary = "Get all strategies for a flag")
    public List<FlagStrategyResponse> getAll(@PathVariable Integer flagId,
                                             @AuthenticationPrincipal UserPrincipal user) {
        List<FlagStrategy> strategies = strategyService.findByFlagId(flagId, user.projectId());
        return strategyAssembler.toResponseList(strategies);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new strategy")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public FlagStrategyResponse create(@PathVariable Integer flagId,
                                       @Valid @RequestBody StrategyRequest request,
                                       @AuthenticationPrincipal UserPrincipal user) {
        request.setFlagId(flagId);
        FlagStrategy strategy = strategyService.create(request, user.projectId());
        return strategyAssembler.toResponse(strategy);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a strategy")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public void delete(@PathVariable Integer flagId,
                       @PathVariable Integer id,
                       @AuthenticationPrincipal UserPrincipal user) {
        strategyService.delete(id, flagId, user.projectId());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a strategy")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public FlagStrategyResponse update(@PathVariable Integer flagId,
                                       @PathVariable Integer id,
                                       @Valid @RequestBody StrategyRequest request,
                                       @AuthenticationPrincipal UserPrincipal user) {
        FlagStrategy strategy = strategyService.update(id, request, flagId, user.projectId());
        return strategyAssembler.toResponse(strategy);
    }

    @PutMapping
    @Operation(summary = "Upsert strategy for flag and environment")
    @PreAuthorize("hasAnyRole('ADMIN', 'DEVELOPER')")
    public FlagStrategyResponse upsert(@PathVariable Integer flagId,
                                       @Valid @RequestBody StrategyRequest request,
                                       @AuthenticationPrincipal UserPrincipal user) {
        request.setFlagId(flagId);
        FlagStrategy strategy = strategyService.upsert(request, user.projectId());
        return strategyAssembler.toResponse(strategy);
    }
}
