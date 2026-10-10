package dev.mozhno.projects;

import dev.mozhno.environments.Environment;
import dev.mozhno.environments.EnvironmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultProjectFactoryTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private EnvironmentRepository environmentRepository;

    @InjectMocks
    private DefaultProjectFactory factory;

    @Test
    void createProject_shouldCreateProjectAndDefaultEnvironments() {
        when(projectRepository.save(any(Project.class))).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            p.setId(10);
            return p;
        });

        Project result = factory.createProject(null, null);

        assertThat(result.getName()).isEqualTo("My Project");
        assertThat(result.getDescription()).isNull();

        ArgumentCaptor<Environment> envCaptor = ArgumentCaptor.forClass(Environment.class);
        verify(environmentRepository, times(3)).save(envCaptor.capture());
        List<Environment> environments = envCaptor.getAllValues();
        assertThat(environments).extracting(Environment::getName)
            .containsExactly("Production", "Staging", "Development");
        assertThat(environments).extracting(Environment::getProjectId).containsOnly(10);
    }

    @Test
    void createDefaultEnvironments_shouldInsertThreeBaselineEnvironments() {
        factory.createDefaultEnvironments(7);

        ArgumentCaptor<Environment> envCaptor = ArgumentCaptor.forClass(Environment.class);
        verify(environmentRepository, times(3)).save(envCaptor.capture());
        assertThat(envCaptor.getAllValues()).extracting(Environment::getProjectId).containsOnly(7);
    }
}
