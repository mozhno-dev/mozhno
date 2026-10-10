package dev.mozhno.auth;

import org.junit.jupiter.api.Test;
import dev.mozhno.BaseIntegrationTest;
import dev.mozhno.apikeys.ApiKeyRepository;
import dev.mozhno.projects.Project;

import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserRepositoryTest extends BaseIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void findByEmail_shouldReturnUser() {
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, role) VALUES ('test@example.com', 'hash123', 'admin')");

        User user = userRepository.findByEmail("test@example.com");

        assertNotNull(user);
        assertEquals("test@example.com", user.getEmail());
        assertEquals("hash123", user.getPasswordHash());
        assertEquals("admin", user.getRole());
        assertNotNull(user.getCreatedAt());
    }

    @Test
    void findByEmail_shouldReturnNullForNonExistent() {
        User user = userRepository.findByEmail("nobody@example.com");
        assertNull(user);
    }

    @Test
    void findById_shouldReturnUser() {
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, role) VALUES ('byid@example.com', 'hash', 'developer')");
        Integer id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'byid@example.com'", Integer.class);

        User user = userRepository.findById(id);

        assertNotNull(user);
        assertEquals("byid@example.com", user.getEmail());
        assertEquals("developer", user.getRole());
    }

    @Test
    void findById_shouldReturnNullForNonExistent() {
        User user = userRepository.findById(9999);
        assertNull(user);
    }

    @Test
    void existsByEmail_shouldReturnTrue() {
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, role) VALUES ('exists@example.com', 'hash', 'viewer')");
        assertTrue(userRepository.existsByEmail("exists@example.com"));
    }

    @Test
    void existsByEmail_shouldReturnFalse() {
        assertFalse(userRepository.existsByEmail("missing@example.com"));
    }

    @Test
    void findAllByIds_shouldReturnUsers() {
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, name, role) VALUES ('a@test.com', 'h1', 'Alice', 'admin')");
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, name, role) VALUES ('b@test.com', 'h2', 'Bob', 'developer')");
        Integer id1 = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'a@test.com'", Integer.class);
        Integer id2 = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'b@test.com'", Integer.class);

        List<User> result = userRepository.findAllByIds(List.of(id1, id2));

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(u -> "Alice".equals(u.getName())));
        assertTrue(result.stream().anyMatch(u -> "Bob".equals(u.getName())));
    }

    @Test
    void findAllByIds_shouldReturnEmptyForEmptyList() {
        List<User> result = userRepository.findAllByIds(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void findAllByIds_shouldReturnEmptyForNull() {
        List<User> result = userRepository.findAllByIds(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void findAllByIds_shouldReturnOnlyMatchingUsers() {
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, name, role) VALUES ('match@test.com', 'h1', 'Match', 'admin')");
        Integer id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'match@test.com'", Integer.class);

        List<User> result = userRepository.findAllByIds(List.of(id, 99999));

        assertEquals(1, result.size());
        assertEquals("Match", result.get(0).getName());
    }

    @Test
    void save_shouldPersistAndReadBackCreatedBy() {
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, role) VALUES ('by-creator@test.com', 'h1', 'admin')");
        Integer creatorId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'by-creator@test.com'", Integer.class);

        User child = new User();
        child.setEmail("child@test.com");
        child.setPasswordHash("hash");
        child.setName("Child");
        child.setRole("developer");
        child.setStatus("active");
        child.setLocale("en");
        child.setCreatedBy(creatorId);

        User saved = userRepository.save(child);
        assertNotNull(saved.getId());
        assertEquals(creatorId, saved.getCreatedBy());

        User reloaded = userRepository.findById(saved.getId());
        assertEquals(creatorId, reloaded.getCreatedBy());
    }

    @Test
    void countByProjectId_shouldCountOnlyUsersOfProject() {
        Project project = new Project();
        project.setName("Count Project");
        Integer projectId = projectRepository.save(project).getId();

        Project other = new Project();
        other.setName("Other Count Project");
        Integer otherProjectId = projectRepository.save(other).getId();

        jdbcTemplate.update(
            "INSERT INTO users (email, password_hash, role, project_id) VALUES (?, ?, ?, ?)",
            "count-a@test.com", "h1", "admin", projectId);
        jdbcTemplate.update(
            "INSERT INTO users (email, password_hash, role, project_id) VALUES (?, ?, ?, ?)",
            "count-b@test.com", "h2", "viewer", projectId);
        jdbcTemplate.update(
            "INSERT INTO users (email, password_hash, role, project_id) VALUES (?, ?, ?, ?)",
            "count-c@test.com", "h3", "viewer", otherProjectId);
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, role) VALUES ('count-none@test.com', 'h4', 'viewer')");

        assertEquals(2, userRepository.countByProjectId(projectId));
        assertEquals(1, userRepository.countByProjectId(otherProjectId));
        assertEquals(0, userRepository.countByProjectId(99999));
    }

    @Test
    void countAdminsCreatedBy_shouldReturnCorrectCount() {
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, role) VALUES ('cnt-parent@test.com', 'h1', 'admin')");
        Integer parentId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'cnt-parent@test.com'", Integer.class);

        jdbcTemplate.execute("INSERT INTO users (email, password_hash, role, created_by) VALUES ('cnt-child-a@test.com', 'h2', 'admin', " + parentId + ")");
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, role, created_by) VALUES ('cnt-child-d@test.com', 'h3', 'developer', " + parentId + ")");

        int count = userRepository.countAdminsCreatedBy(parentId, parentId);
        assertEquals(1, count);
    }

    @Test
    void countByCreatedBy_shouldReturnCorrectCount() {
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, role) VALUES ('by-boss@test.com', 'h1', 'admin')");
        Integer bossId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'by-boss@test.com'", Integer.class);

        jdbcTemplate.execute("INSERT INTO users (email, password_hash, role, created_by) VALUES ('by-sub1@test.com', 'h2', 'developer', " + bossId + ")");
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, role, created_by) VALUES ('by-sub2@test.com', 'h3', 'viewer', " + bossId + ")");

        assertEquals(2, userRepository.countByCreatedBy(bossId));
    }

    @Test
    void touchActivity_whenNeverActive_setsTimestamp() {
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, role) VALUES ('touch-new@test.com', 'h', 'admin')");
        Integer id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'touch-new@test.com'", Integer.class);

        userRepository.touchActivity(id);

        assertNotNull(userRepository.findById(id).getLastActiveAt());
    }

    @Test
    void touchActivity_withinWindow_isThrottled() {
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, role) VALUES ('touch-hot@test.com', 'h', 'admin')");
        Integer id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'touch-hot@test.com'", Integer.class);

        userRepository.touchActivity(id);
        java.sql.Timestamp first = jdbcTemplate.queryForObject(
            "SELECT last_active_at FROM users WHERE id = ?", java.sql.Timestamp.class, id);

        userRepository.touchActivity(id);
        java.sql.Timestamp second = jdbcTemplate.queryForObject(
            "SELECT last_active_at FROM users WHERE id = ?", java.sql.Timestamp.class, id);

        assertEquals(first, second);
    }

    @Test
    void touchActivity_afterWindow_updates() {
        jdbcTemplate.execute("INSERT INTO users (email, password_hash, role) VALUES ('touch-cold@test.com', 'h', 'admin')");
        Integer id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = 'touch-cold@test.com'", Integer.class);
        jdbcTemplate.update(
            "UPDATE users SET last_active_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes' WHERE id = ?", id);

        userRepository.touchActivity(id);

        java.sql.Timestamp updated = jdbcTemplate.queryForObject(
            "SELECT last_active_at FROM users WHERE id = ?", java.sql.Timestamp.class, id);
        assertTrue(updated.toInstant().isAfter(Instant.now().minus(5, ChronoUnit.MINUTES)));
    }

    @Test
    void touchActivity_unknownUser_isNoOp() {
        assertDoesNotThrow(() -> userRepository.touchActivity(99999));
    }
}