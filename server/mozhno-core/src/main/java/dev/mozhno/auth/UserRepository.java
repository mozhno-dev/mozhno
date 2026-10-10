package dev.mozhno.auth;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JDBC-based repository for user persistence.
 *
 * <p>Provides CRUD operations and status/role updates against the {@code users} table.
 * Lookup by email and by id; uses manual row mapping via {@link JdbcTemplate}.</p>
 */
@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;
    private final AuthProperties authProperties;

    public UserRepository(JdbcTemplate jdbc, AuthProperties authProperties) {
        this.jdbc = jdbc;
        this.authProperties = authProperties;
    }

    private static final RowMapper<User> ROW_MAPPER = (rs, _) -> {
        User u = new User();
        u.setId(rs.getInt("id"));
        u.setEmail(rs.getString("email"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setName(rs.getString("name"));
        u.setRole(rs.getString("role"));
        u.setStatus(rs.getString("status"));
        u.setAvatar(rs.getString("avatar"));
        u.setLocale(rs.getString("locale"));
        u.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        Timestamp lastActive = rs.getTimestamp("last_active_at");
        u.setLastActiveAt(lastActive != null ? lastActive.toInstant() : null);
        u.setFailedLoginAttempts(rs.getInt("failed_login_attempts"));
        Timestamp lockedUntil = rs.getTimestamp("locked_until");
        u.setLockedUntil(lockedUntil != null ? lockedUntil.toInstant() : null);
        Integer projectId = rs.getObject("project_id", Integer.class);
        if (!rs.wasNull()) u.setProjectId(projectId);
        Integer createdBy = rs.getObject("created_by", Integer.class);
        if (!rs.wasNull()) u.setCreatedBy(createdBy);
        return u;
    };

    private static final String USER_COLUMNS = "id, email, password_hash, name, role, status, avatar, locale, created_at, last_active_at, failed_login_attempts, locked_until, project_id, created_by";

    public User findByEmail(String email) {
        try {
            return jdbc.queryForObject(
                "SELECT " + USER_COLUMNS + " FROM users WHERE email = ?",
                ROW_MAPPER, email);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public User findById(Integer id) {
        try {
            return jdbc.queryForObject(
                "SELECT " + USER_COLUMNS + " FROM users WHERE id = ?",
                ROW_MAPPER, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public List<User> findByProjectId(Integer projectId) {
        return jdbc.query(
            "SELECT " + USER_COLUMNS + " FROM users WHERE project_id = ? ORDER BY created_at DESC",
            ROW_MAPPER, projectId);
    }

    public boolean existsByEmail(String email) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        return count != null && count > 0;
    }

    public int count() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users", Integer.class);
        return count != null ? count : 0;
    }

    public int countByProjectId(Integer projectId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE project_id = ?", Integer.class, projectId);
        return count != null ? count : 0;
    }

    public User save(User user) {
        if (user.getId() == null) {
            jdbc.update(
                "INSERT INTO users (email, password_hash, name, role, status, avatar, locale, project_id, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                user.getEmail(), user.getPasswordHash(), user.getName(), user.getRole(), user.getStatus(), user.getAvatar(), user.getLocale(), user.getProjectId(), user.getCreatedBy());
            return findByEmail(user.getEmail());
        } else {
            jdbc.update(
                "UPDATE users SET email = ?, password_hash = ?, name = ?, role = ?, status = ?, avatar = ?, locale = ?, project_id = ?, created_by = ? WHERE id = ?",
                user.getEmail(), user.getPasswordHash(), user.getName(), user.getRole(), user.getStatus(), user.getAvatar(), user.getLocale(), user.getProjectId(), user.getCreatedBy(), user.getId());
            return findById(user.getId());
        }
    }

    public void updateStatus(Integer id, String status) {
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", status, id);
    }

    public void updateRole(Integer id, String role) {
        jdbc.update("UPDATE users SET role = ? WHERE id = ?", role, id);
    }

    /**
     * Records user activity by refreshing {@code last_active_at}, throttled to
     * at most one write per {@code mozhno.auth.activity-window-minutes} per user.
     * The staleness check runs in SQL so no in-memory state is needed and
     * concurrent requests are safe.
     */
    public void touchActivity(Integer id) {
        jdbc.update("""
            UPDATE users SET last_active_at = CURRENT_TIMESTAMP
            WHERE id = ? AND (last_active_at IS NULL
                OR last_active_at < CURRENT_TIMESTAMP - MAKE_INTERVAL(mins => ?))
            """, id, authProperties.getActivityWindowMinutes());
    }

    public void delete(Integer id) {
        jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    public List<User> findAllByIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.query(
            "SELECT " + USER_COLUMNS + " FROM users WHERE id IN (" + placeholders + ")",
            ROW_MAPPER, ids.toArray());
    }

    public void incrementFailedAttempts(Integer id) {
        jdbc.update("UPDATE users SET failed_login_attempts = failed_login_attempts + 1 WHERE id = ?", id);
    }

    public void resetFailedAttempts(Integer id) {
        jdbc.update("UPDATE users SET failed_login_attempts = 0, locked_until = NULL WHERE id = ?", id);
    }

    public void lockUser(Integer id, Instant until) {
        jdbc.update("UPDATE users SET locked_until = ? WHERE id = ?", Timestamp.from(until), id);
    }

    /**
     * Returns the avatar binary data for a user.
     *
     * @param id the user ID
     * @return avatar bytes, or null if no avatar is set
     */
    public byte[] getAvatarData(Integer id) {
        try {
            return jdbc.queryForObject("SELECT avatar_data FROM users WHERE id = ?", byte[].class, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * Updates the avatar binary data for a user.
     *
     * @param id   the user ID
     * @param data the avatar bytes
     */
    public void updateAvatarData(Integer id, byte[] data) {
        jdbc.update("UPDATE users SET avatar_data = ? WHERE id = ?", data, id);
    }

    /** Counts how many users (excluding the given ID) were created by this user and have admin role. */
    public int countAdminsCreatedBy(Integer creatorId, Integer excludeId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE created_by = ? AND role = 'admin' AND id != ?",
            Integer.class, creatorId, excludeId);
        return count != null ? count : 0;
    }

    /** Counts how many users were created by this user (used to check if group has children). */
    public int countByCreatedBy(Integer creatorId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE created_by = ?",
            Integer.class, creatorId);
        return count != null ? count : 0;
    }
}