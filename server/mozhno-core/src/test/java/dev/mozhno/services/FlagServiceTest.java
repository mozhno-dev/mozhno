package dev.mozhno.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import dev.mozhno.events.DomainEventPublisher;
import dev.mozhno.flags.*;
import dev.mozhno.spi.QuotaSpi;
import dev.mozhno.tags.Tag;
import dev.mozhno.tags.TagRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlagServiceTest {

    @Mock
    private FlagRepository flagRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private FlagTagValueRepository flagTagValueRepository;

    @Mock
    private DomainEventPublisher events;

    @Mock
    private QuotaSpi quotaSpi;

    private FlagService flagService;

    @BeforeEach
    void setUp() {
        flagService = new FlagService(flagRepository, tagRepository, flagTagValueRepository, events, quotaSpi,
            new FlagsProperties());
        lenient().when(quotaSpi.canCreateFlag(any())).thenReturn(new QuotaSpi.Allowed());
    }

    @Test
    void findById_shouldReturnFlag() {
        Flag flag = new Flag();
        flag.setId(1);
        flag.setName("test");
        when(flagRepository.findByIdAndProjectId(1, null)).thenReturn(flag);

        Flag result = flagService.findById(1, null);
        assertEquals("test", result.getName());
    }

    @Test
    void findById_shouldThrowExceptionWhenNotFound() {
        when(flagRepository.findByIdAndProjectId(999, null)).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> flagService.findById(999, null));
        assertTrue(ex.getMessage().contains("Flag not found"));
    }

    @Test
    void unarchive_shouldThrowExceptionWhenNotFound() {
        when(flagRepository.clearArchived(eq(999), any())).thenReturn(0);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> flagService.unarchive(999, null));
        assertTrue(ex.getMessage().contains("Flag not found"));
    }

    @Test
    void findByProjectIdIncludingArchived_shouldReturnAllFlags() {
        when(flagRepository.findByProjectIdIncludingArchived(1)).thenReturn(List.of(new Flag()));

        List<Flag> result = flagService.findByProjectIdIncludingArchived(1);
        assertEquals(1, result.size());
    }

    @Test
    void countActiveByProjectId_shouldDelegateToRepository() {
        when(flagRepository.countActiveByProjectId(1)).thenReturn(3);

        assertEquals(3, flagService.countActiveByProjectId(1));
    }

    @Test
    void create_shouldRejectTagOfAnotherProject() {
        FlagRequest request = new FlagRequest();
        request.setProjectId(100);
        request.setName("tagged");
        request.setKey("tagged");
        request.setTags(List.of(new FlagRequest.TagValue(7, "v")));
        when(flagRepository.save(any(Flag.class))).thenAnswer(inv -> {
            Flag f = inv.getArgument(0);
            f.setId(1);
            return f;
        });
        when(tagRepository.findAllByIds(List.of(7), 100)).thenReturn(List.of());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> flagService.create(request, 1));
        assertTrue(ex.getMessage().contains("Tag not found"));
        verify(flagTagValueRepository, never()).saveBatch(anyInt(), any());
    }

    @Test
    void update_shouldRejectTagOfAnotherProject() {
        Flag flag = new Flag();
        flag.setId(1);
        flag.setProjectId(100);
        when(flagRepository.findByIdAndProjectId(1, 100)).thenReturn(flag);
        when(flagRepository.save(any(Flag.class))).thenReturn(flag);
        when(tagRepository.findAllByIds(List.of(7), 100)).thenReturn(List.of());

        FlagRequest request = new FlagRequest();
        request.setProjectId(100);
        request.setName("tagged");
        request.setKey("tagged");
        request.setTags(List.of(new FlagRequest.TagValue(7, "v")));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> flagService.update(1, request));
        assertTrue(ex.getMessage().contains("Tag not found"));
        verify(flagTagValueRepository, never()).saveBatch(anyInt(), any());
    }
}