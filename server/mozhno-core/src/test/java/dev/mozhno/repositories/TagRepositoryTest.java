package dev.mozhno.repositories;

import org.junit.jupiter.api.Test;
import dev.mozhno.BaseIntegrationTest;
import dev.mozhno.tags.Tag;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TagRepositoryTest extends BaseIntegrationTest {

    private Integer createProject() {
        dev.mozhno.projects.Project p = new dev.mozhno.projects.Project();
        p.setName("Test Project");
        return projectRepository.save(p).getId();
    }

    @Test
    void findByProjectId_shouldReturnTags() {
        Integer projectId = createProject();
        Tag t1 = new Tag();
        t1.setName("tag1");
        t1.setColor("#FF0000");
        t1.setProjectId(projectId);
        tagRepository.save(t1);
        Tag t2 = new Tag();
        t2.setName("tag2");
        t2.setColor("#00FF00");
        t2.setProjectId(projectId);
        tagRepository.save(t2);

        List<Tag> result = tagRepository.findByProjectId(projectId);
        assertEquals(2, result.size());
    }

    @Test
    void findById_shouldReturnTag() {
        Integer projectId = createProject();
        Tag t = new Tag();
        t.setName("find-tag");
        t.setColor("#0000FF");
        t.setProjectId(projectId);
        Tag saved = tagRepository.save(t);

        Tag found = tagRepository.findById(saved.getId());
        assertNotNull(found);
        assertEquals("find-tag", found.getName());
    }

    @Test
    void findById_shouldReturnNullForNonExistent() {
        assertNull(tagRepository.findById(9999));
    }

    @Test
    void save_shouldInsertNewTag() {
        Integer projectId = createProject();
        Tag t = new Tag();
        t.setName("new-tag");
        t.setColor("#ABC123");
        t.setProjectId(projectId);

        Tag saved = tagRepository.save(t);
        assertNotNull(saved.getId());
        assertEquals("new-tag", saved.getName());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void save_shouldUpdateExistingTag() {
        Integer projectId = createProject();
        Tag t = new Tag();
        t.setName("old");
        t.setColor("#000000");
        t.setProjectId(projectId);
        Tag saved = tagRepository.save(t);

        saved.setName("updated");
        saved.setColor("#FFFFFF");
        tagRepository.save(saved);

        Tag found = tagRepository.findById(saved.getId());
        assertEquals("updated", found.getName());
        assertEquals("#FFFFFF", found.getColor());
    }

    @Test
    void deleteById_shouldRemoveTag() {
        Integer projectId = createProject();
        Tag t = new Tag();
        t.setName("delete-me");
        t.setColor("#000");
        t.setProjectId(projectId);
        Tag saved = tagRepository.save(t);

        tagRepository.deleteById(saved.getId(), saved.getProjectId());
        assertNull(tagRepository.findById(saved.getId()));
    }

    @Test
    void findAllByIds_shouldReturnTags() {
        Integer projectId = createProject();
        Tag t1 = new Tag();
        t1.setName("a");
        t1.setColor("#111");
        t1.setProjectId(projectId);
        Tag s1 = tagRepository.save(t1);
        Tag t2 = new Tag();
        t2.setName("b");
        t2.setColor("#222");
        t2.setProjectId(projectId);
        Tag s2 = tagRepository.save(t2);

        List<Tag> result = tagRepository.findAllByIds(List.of(s1.getId(), s2.getId()), projectId);
        assertEquals(2, result.size());
    }

    @Test
    void findAllByIds_shouldExcludeTagsOfAnotherProject() {
        Integer projectId = createProject();
        Integer otherProjectId = createProject();
        Tag own = new Tag();
        own.setName("own");
        own.setColor("#111");
        own.setProjectId(projectId);
        Tag savedOwn = tagRepository.save(own);
        Tag foreign = new Tag();
        foreign.setName("foreign");
        foreign.setColor("#222");
        foreign.setProjectId(otherProjectId);
        Tag savedForeign = tagRepository.save(foreign);

        List<Tag> result = tagRepository.findAllByIds(
            List.of(savedOwn.getId(), savedForeign.getId()), projectId);

        assertEquals(1, result.size());
        assertEquals(savedOwn.getId(), result.get(0).getId());
    }

    @Test
    void findAllByIds_shouldReturnEmptyForEmptyList() {
        List<Tag> result = tagRepository.findAllByIds(List.of(), createProject());
        assertTrue(result.isEmpty());
    }

    @Test
    void findAllByIds_shouldReturnEmptyForNull() {
        List<Tag> result = tagRepository.findAllByIds(null, createProject());
        assertTrue(result.isEmpty());
    }
}