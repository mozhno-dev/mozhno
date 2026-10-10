package dev.mozhno.projects;

import dev.mozhno.environments.Environment;
import dev.mozhno.environments.EnvironmentRepository;
import org.springframework.stereotype.Component;

/**
 * Creates the baseline project bootstrap shared by every provisioning path
 * (self signup, OAuth first login, invite fallback): a default project and its
 * Production/Staging/Development environments. Environments are inserted
 * through the repository so plan quotas only apply to environments created by
 * the user afterwards.
 */
@Component
public class DefaultProjectFactory {

    public static final String DEFAULT_PROJECT_NAME = "My Project";

    private final ProjectRepository projectRepository;
    private final EnvironmentRepository environmentRepository;

    public DefaultProjectFactory(ProjectRepository projectRepository,
                                 EnvironmentRepository environmentRepository) {
        this.projectRepository = projectRepository;
        this.environmentRepository = environmentRepository;
    }

    /**
     * Creates a project with the default environments.
     *
     * @param name        project name ({@code My Project} when null)
     * @param description optional project description
     * @return the created project
     */
    public Project createProject(String name, String description) {
        Project project = new Project();
        project.setName(name != null ? name : DEFAULT_PROJECT_NAME);
        project.setDescription(description);
        Project saved = projectRepository.save(project);
        createDefaultEnvironments(saved.getId());
        return saved;
    }

    /**
     * Creates the three baseline environments for an existing project.
     *
     * @param projectId the owning project ID
     */
    public void createDefaultEnvironments(Integer projectId) {
        createEnvironment(projectId, "Production", "Live environment", "#2d9484");
        createEnvironment(projectId, "Staging", "Pre-production testing", "#e67e22");
        createEnvironment(projectId, "Development", "Local and shared development", "#3498db");
    }

    private void createEnvironment(Integer projectId, String name, String description, String color) {
        Environment environment = new Environment();
        environment.setProjectId(projectId);
        environment.setName(name);
        environment.setDescription(description);
        environment.setColor(color);
        environmentRepository.save(environment);
    }
}
