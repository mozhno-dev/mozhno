package dev.mozhno.auth;

import dev.mozhno.events.DomainEventPublisher;
import dev.mozhno.exception.ConflictException;
import dev.mozhno.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Integer PROJECT_ID = 7;

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private DomainEventPublisher events;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, events);
    }

    private User projectUser(int id, String role, Integer projectId) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setProjectId(projectId);
        return user;
    }

    @Test
    void findById_shouldReturnUserInProject() {
        User user = projectUser(1, "developer", PROJECT_ID);
        when(userRepository.findById(1)).thenReturn(user);

        UserDto dto = userService.findById(1, PROJECT_ID);

        assertEquals(1, dto.id());
    }

    @Test
    void findById_shouldThrowWhenUserInAnotherProject() {
        when(userRepository.findById(1)).thenReturn(projectUser(1, "developer", 8));

        assertThrows(NotFoundException.class, () -> userService.findById(1, PROJECT_ID));
    }

    @Test
    void findById_shouldThrowWhenUserHasNoProject() {
        when(userRepository.findById(1)).thenReturn(projectUser(1, "developer", null));

        assertThrows(NotFoundException.class, () -> userService.findById(1, PROJECT_ID));
    }

    @Test
    void delete_shouldAllowLastAdminDeletionWhenNoChildren() {
        User user = projectUser(1, "admin", PROJECT_ID);
        when(userRepository.findById(1)).thenReturn(user);
        when(userRepository.countByCreatedBy(1)).thenReturn(0);

        assertDoesNotThrow(() -> userService.delete(1, PROJECT_ID));
        verify(userRepository).delete(1);
    }

    @Test
    void delete_shouldAllowAdminDeletionWhenOtherAdminInGroup() {
        User user = projectUser(1, "admin", PROJECT_ID);
        when(userRepository.findById(1)).thenReturn(user);
        when(userRepository.countByCreatedBy(1)).thenReturn(3);
        when(userRepository.countAdminsCreatedBy(1, 1)).thenReturn(1);

        assertDoesNotThrow(() -> userService.delete(1, PROJECT_ID));
        verify(userRepository).delete(1);
    }

    @Test
    void delete_shouldBlockLastAdminDeletionWithChildren() {
        User user = projectUser(1, "admin", PROJECT_ID);
        when(userRepository.findById(1)).thenReturn(user);
        when(userRepository.countByCreatedBy(1)).thenReturn(2);
        when(userRepository.countAdminsCreatedBy(1, 1)).thenReturn(0);

        ConflictException ex = assertThrows(ConflictException.class, () -> userService.delete(1, PROJECT_ID));
        assertTrue(ex.getMessage().contains("last admin"));
        verify(userRepository, never()).delete(anyInt());
    }

    @Test
    void delete_shouldAllowNonAdminDeletionEvenWithChildren() {
        User user = projectUser(1, "developer", PROJECT_ID);
        when(userRepository.findById(1)).thenReturn(user);

        assertDoesNotThrow(() -> userService.delete(1, PROJECT_ID));
        verify(userRepository).delete(1);
        verify(userRepository, never()).countByCreatedBy(anyInt());
    }

    @Test
    void delete_shouldThrowWhenUserNotFound() {
        when(userRepository.findById(99)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> userService.delete(99, PROJECT_ID));
        verify(userRepository, never()).delete(99);
    }

    @Test
    void delete_shouldThrowWhenUserInAnotherProject() {
        when(userRepository.findById(1)).thenReturn(projectUser(1, "viewer", 8));

        assertThrows(NotFoundException.class, () -> userService.delete(1, PROJECT_ID));
        verify(userRepository, never()).delete(anyInt());
    }

    @Test
    void update_demotingLastAdminWithChildren_shouldThrow() {
        User user = projectUser(1, "admin", PROJECT_ID);
        when(userRepository.findById(1)).thenReturn(user);
        when(userRepository.countByCreatedBy(1)).thenReturn(2);
        when(userRepository.countAdminsCreatedBy(1, 1)).thenReturn(0);

        UserUpdateRequest request = new UserUpdateRequest(null, "developer", null, null);

        ConflictException ex = assertThrows(ConflictException.class, () -> userService.update(1, request, PROJECT_ID));
        assertTrue(ex.getMessage().contains("last admin"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void update_shouldThrowWhenUserInAnotherProject() {
        when(userRepository.findById(1)).thenReturn(projectUser(1, "viewer", 8));

        UserUpdateRequest request = new UserUpdateRequest(null, "developer", null, null);

        assertThrows(NotFoundException.class, () -> userService.update(1, request, PROJECT_ID));
        verify(userRepository, never()).save(any());
    }

    @Test
    void update_demotingAdminWithOtherAdminInGroup_shouldSucceed() {
        User user = projectUser(1, "admin", PROJECT_ID);
        when(userRepository.findById(1)).thenReturn(user);
        when(userRepository.countByCreatedBy(1)).thenReturn(3);
        when(userRepository.countAdminsCreatedBy(1, 1)).thenReturn(1);
        when(userRepository.save(any(User.class))).thenReturn(user);

        UserUpdateRequest request = new UserUpdateRequest(null, "developer", null, null);

        assertDoesNotThrow(() -> userService.update(1, request, PROJECT_ID));
        verify(userRepository).save(any(User.class));
    }

    @Test
    void update_demotingAdminWithoutChildren_shouldSucceed() {
        User user = projectUser(1, "admin", PROJECT_ID);
        when(userRepository.findById(1)).thenReturn(user);
        when(userRepository.countByCreatedBy(1)).thenReturn(0);
        when(userRepository.save(any(User.class))).thenReturn(user);

        UserUpdateRequest request = new UserUpdateRequest(null, "viewer", null, null);

        assertDoesNotThrow(() -> userService.update(1, request, PROJECT_ID));
        verify(userRepository).save(any(User.class));
    }

    @Test
    void update_promotingNonAdmin_shouldNotCheckLastAdmin() {
        User user = projectUser(1, "developer", PROJECT_ID);
        when(userRepository.findById(1)).thenReturn(user);
        when(userRepository.save(any(User.class))).thenReturn(user);

        UserUpdateRequest request = new UserUpdateRequest(null, "admin", null, null);

        assertDoesNotThrow(() -> userService.update(1, request, PROJECT_ID));
        verify(userRepository).save(any(User.class));
        verify(userRepository, never()).countByCreatedBy(anyInt());
    }

    @Test
    void update_statusOnly_shouldNotTouchRoleOrPassword() {
        User user = projectUser(1, "developer", PROJECT_ID);
        user.setStatus("active");
        when(userRepository.findById(1)).thenReturn(user);
        when(userRepository.save(any(User.class))).thenReturn(user);

        UserUpdateRequest request = new UserUpdateRequest(null, null, "suspended", null);

        assertDoesNotThrow(() -> userService.update(1, request, PROJECT_ID));
        verify(userRepository).save(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void uploadAvatar_shouldThrowWhenUserInAnotherProject() {
        when(userRepository.findById(1)).thenReturn(projectUser(1, "viewer", 8));
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3});

        assertThrows(NotFoundException.class, () -> userService.uploadAvatar(1, file, PROJECT_ID));
        verify(userRepository, never()).updateAvatarData(anyInt(), any());
    }
}
