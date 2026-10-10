package dev.mozhno.auth;

import dev.mozhno.BaseIntegrationTest;
import dev.mozhno.projects.Project;
import dev.mozhno.projects.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the user-project lifecycle.
 * Projects are created once and cannot be deleted — only reset.
 */
class UserProjectLifecycleTest extends BaseIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User createAdmin(String email) {
        Project project = new Project();
        project.setName("Lifecycle " + email);
        Integer projectId = projectRepository.save(project).getId();

        User admin = new User();
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode("password1"));
        admin.setName("Admin");
        admin.setRole("admin");
        admin.setStatus("active");
        admin.setLocale("en");
        admin.setProjectId(projectId);
        return userRepository.save(admin);
    }

    private User createUser(String email, String role, Integer createdBy, Integer projectId) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode("password1"));
        u.setName("User " + email);
        u.setRole(role);
        u.setStatus("active");
        u.setLocale("en");
        if (createdBy != null) u.setCreatedBy(createdBy);
        if (projectId != null) u.setProjectId(projectId);
        return userRepository.save(u);
    }

    // ══════════════════════════════════════════════════════════════════
    // Admin and children share the same project
    // ══════════════════════════════════════════════════════════════════

    @Test
    void adminAndChildren_shareProject() {
        User admin = createAdmin("lifecycle-admin@test.com");
        User dev = createUser("lifecycle-dev@test.com", "developer", admin.getId(), admin.getProjectId());

        assertEquals(admin.getProjectId(), userRepository.findById(dev.getId()).getProjectId());
    }

    // ══════════════════════════════════════════════════════════════════
    // Reset project: clears data, name becomes "My Project"
    // ══════════════════════════════════════════════════════════════════

    @Test
    void resetProject_shouldClearDataAndKeepUsersLinked() {
        Integer projectId = jdbcTemplate.queryForObject(
            "INSERT INTO projects (name) VALUES ('Reset Me') RETURNING id", Integer.class);
        User admin = createAdmin("reset-admin@test.com");
        admin.setProjectId(projectId);
        userRepository.save(admin);

        dev.mozhno.projects.Project project = projectService.reset(projectId);

        assertEquals("My Project", project.getName());
        assertNull(project.getLogo());
        assertEquals(projectId, userRepository.findById(admin.getId()).getProjectId());
    }

    // ══════════════════════════════════════════════════════════════════
    // Last admin deletion/demotion protection
    // ══════════════════════════════════════════════════════════════════

    @Test
    void lastAdminWithChildren_cannotBeDeleted() {
        User admin = createAdmin("prot-admin@test.com");
        createUser("prot-dev@test.com", "developer", admin.getId(), admin.getProjectId());

        dev.mozhno.exception.ConflictException ex = assertThrows(
            dev.mozhno.exception.ConflictException.class,
            () -> userService.delete(admin.getId(), admin.getProjectId()));
        assertTrue(ex.getMessage().contains("last admin"));
        assertNotNull(userRepository.findById(admin.getId()));
    }

    @Test
    void adminWithOtherAdminInGroup_canBeDeleted() {
        User admin = createAdmin("del-admin@test.com");
        createUser("del-coadmin@test.com", "admin", admin.getId(), admin.getProjectId());

        assertDoesNotThrow(() -> userService.delete(admin.getId(), admin.getProjectId()));
        assertNull(userRepository.findById(admin.getId()));
    }

    @Test
    void lastAdminWithChildren_cannotBeDemoted() {
        User admin = createAdmin("demo-admin@test.com");
        createUser("demo-dev@test.com", "developer", admin.getId(), admin.getProjectId());

        var request = new UserUpdateRequest(null, "developer", null, null);
        var ex = assertThrows(dev.mozhno.exception.ConflictException.class,
            () -> userService.update(admin.getId(), request, admin.getProjectId()));
        assertTrue(ex.getMessage().contains("last admin"));
        assertEquals("admin", userRepository.findById(admin.getId()).getRole());
    }

    // ══════════════════════════════════════════════════════════════════
    // Different admins: children follow their creator
    // ══════════════════════════════════════════════════════════════════

    @Test
    void differentAdmins_childrenFollowCreator() {
        User adminA = createAdmin("group-a@test.com");
        User devA = createUser("dev-ga@test.com", "developer", adminA.getId(), adminA.getProjectId());

        User adminB = createAdmin("group-b@test.com");
        User devB = createUser("dev-gb@test.com", "viewer", adminB.getId(), adminB.getProjectId());

        assertEquals(adminA.getProjectId(), userRepository.findById(devA.getId()).getProjectId());
        assertEquals(adminB.getProjectId(), userRepository.findById(devB.getId()).getProjectId());
    }

    // ══════════════════════════════════════════════════════════════════
    // Project-scoped reads
    // ══════════════════════════════════════════════════════════════════

    @Test
    void findAll_shouldReturnOnlyUsersOfProject() {
        User adminA = createAdmin("list-a@test.com");
        User devA = createUser("list-dev-a@test.com", "developer", adminA.getId(), adminA.getProjectId());
        User adminB = createAdmin("list-b@test.com");

        java.util.List<UserDto> users = userService.findAll(adminA.getProjectId());

        assertEquals(2, users.size());
        assertTrue(users.stream().anyMatch(u -> u.id().equals(adminA.getId())));
        assertTrue(users.stream().anyMatch(u -> u.id().equals(devA.getId())));
        assertFalse(users.stream().anyMatch(u -> u.id().equals(adminB.getId())));
    }

    @Test
    void findById_shouldThrowForUserOfAnotherProject() {
        User adminA = createAdmin("cross-a@test.com");
        User adminB = createAdmin("cross-b@test.com");

        assertThrows(dev.mozhno.exception.NotFoundException.class,
            () -> userService.findById(adminB.getId(), adminA.getProjectId()));
    }
}
