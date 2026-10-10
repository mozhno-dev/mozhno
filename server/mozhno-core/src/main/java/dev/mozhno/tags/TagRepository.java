package dev.mozhno.tags;

import dev.mozhno.CacheNames;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * JDBC-based repository for {@link Tag} entities.
 */
@Repository
public class TagRepository {
    private final JdbcTemplate jdbc;

    public TagRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Tag> ROW_MAPPER = (rs, rowNum) -> {
        Tag t = new Tag();
        t.setId(rs.getInt("id"));
        t.setName(rs.getString("name"));
        t.setDescription(rs.getString("description"));
        t.setColor(rs.getString("color"));
        t.setProjectId(rs.getInt("project_id"));
        t.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return t;
    };

    /**
     * Returns all tags for a project.
     *
     * @param projectId the project ID
     * @return list of tags
     */
    @Cacheable(CacheNames.TAGS)
    public List<Tag> findByProjectId(Integer projectId) {
        return jdbc.query("SELECT id, name, description, color, project_id, created_at FROM tags WHERE project_id = ? ORDER BY id", ROW_MAPPER, projectId);
    }

    /**
     * Finds a tag by its ID.
     *
     * @param id the tag ID
     * @return the tag, or null if not found
     */
    public Tag findById(Integer id) {
        try {
            return jdbc.queryForObject("SELECT id, name, description, color, project_id, created_at FROM tags WHERE id = ?", ROW_MAPPER, id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    public Tag findByIdAndProjectId(Integer id, Integer projectId) {
        try {
            return jdbc.queryForObject("SELECT id, name, description, color, project_id, created_at FROM tags WHERE id = ? AND project_id = ?", ROW_MAPPER, id, projectId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * Inserts or updates a tag.
     *
     * @param tag the tag to save
     * @return the saved tag
     */
    @CacheEvict(value = CacheNames.TAGS, allEntries = true)
    public Tag save(Tag tag) {
        if (tag.getId() == null) {
            Instant createTime = Instant.now();
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO tags (name, description, color, project_id, created_at) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
                ps.setString(1, tag.getName());
                ps.setString(2, tag.getDescription());
                ps.setString(3, tag.getColor());
                ps.setInt(4, tag.getProjectId());
                ps.setTimestamp(5, Timestamp.from(createTime));
                return ps;
            }, keyHolder);
            tag.setId(keyHolder.getKey().intValue());
            tag.setCreatedAt(createTime);
        } else {
            jdbc.update("UPDATE tags SET name = ?, description = ?, color = ? WHERE id = ? AND project_id = ?",
                tag.getName(), tag.getDescription(), tag.getColor(), tag.getId(), tag.getProjectId());
        }
        return tag;
    }

    /**
     * Deletes a tag by its ID and project ID.
     *
     * @param id the tag ID
     * @param projectId the project ID
     * @return number of deleted rows
     */
    @CacheEvict(value = CacheNames.TAGS, allEntries = true)
    public int deleteById(Integer id, Integer projectId) {
        return jdbc.update("DELETE FROM tags WHERE id = ? AND project_id = ?", id, projectId);
    }

    /**
     * Returns the tags with the given IDs that belong to the given project.
     *
     * @param ids list of tag IDs
     * @param projectId the owning project ID
     * @return list of tags, empty if no IDs provided
     */
    @Cacheable(CacheNames.TAGS)
    public List<Tag> findAllByIds(List<Integer> ids, Integer projectId) {
        if (ids == null || ids.isEmpty()) return List.of();
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        List<Object> args = new java.util.ArrayList<>(ids);
        args.add(projectId);
        return jdbc.query("SELECT id, name, description, color, project_id, created_at FROM tags WHERE id IN ("
            + placeholders + ") AND project_id = ?", ROW_MAPPER, args.toArray());
    }

    public void deleteByProjectId(Integer projectId) {
        jdbc.update("DELETE FROM tags WHERE project_id = ?", projectId);
    }

}