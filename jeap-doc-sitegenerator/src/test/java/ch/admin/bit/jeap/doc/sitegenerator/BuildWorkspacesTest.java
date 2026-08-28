package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BuildWorkspacesTest {

    @TempDir
    Path root;

    private BuildProperties properties;
    private BuildWorkspaces workspaces;

    @BeforeEach
    void setUp() {
        properties = new BuildProperties();
        properties.setWorkspaceDirectory(root);
        workspaces = new BuildWorkspaces(properties);
    }

    @Test
    void create_thenAFreshDirectoryNamedAfterTheBuild() throws IOException {
        Path workspace = workspaces.create(42);

        assertThat(workspace).isDirectory().hasFileName("42").hasParent(root);
    }

    @Test
    void create_whenAPreviousAttemptLeftSomething_thenItIsGone() throws IOException {
        Files.writeString(Files.createDirectories(workspaces.of(42)).resolve("leftover.txt"), "old");

        assertThat(workspaces.create(42)).isEmptyDirectory();
    }

    @Test
    void discard_thenTheWorkspaceIsRemoved() throws IOException {
        workspaces.create(42);

        workspaces.discard(42);

        assertThat(workspaces.of(42)).doesNotExist();
    }

    @Test
    void discard_whenKeepWorkspaceIsOn_thenItIsKept() throws IOException {
        properties.setKeepWorkspace(true);
        workspaces.create(42);

        workspaces.discard(42);

        assertThat(workspaces.of(42)).isDirectory();
    }

    @Test
    void sweep_thenOnlyTheWorkspacesOfBuildsThatAreNoLongerRunningGo() throws IOException {
        workspaces.create(1);
        workspaces.create(2);
        workspaces.create(3);

        assertThat(workspaces.sweep(Set.of(2L))).isEqualTo(2);

        assertThat(workspaces.of(1)).doesNotExist();
        assertThat(workspaces.of(3)).doesNotExist();
        assertThat(workspaces.of(2)).isDirectory();
    }

    /**
     * The criterion is the state of the build, not who created the directory - which is what lets an instance
     * sweep at startup and before every build without ever touching a build another instance is running.
     */
    @Test
    void sweep_whenAnotherInstanceIsBuilding_thenItsWorkspaceIsUntouched() throws IOException {
        Path ofAnotherInstance = workspaces.create(77);

        workspaces.sweep(Set.of(77L));

        assertThat(ofAnotherInstance).isDirectory();
    }

    @Test
    void sweep_whenNothingIsRunning_thenEverythingGoes() throws IOException {
        workspaces.create(1);
        workspaces.create(2);

        assertThat(workspaces.sweep(Set.of())).isEqualTo(2);
        assertThat(root).isEmptyDirectory();
    }

    @Test
    void sweep_whenSomethingElseIsInTheDirectory_thenItIsLeftAlone() throws IOException {
        Files.createDirectory(root.resolve("not-a-build"));

        assertThat(workspaces.sweep(Set.of())).isZero();
        assertThat(root.resolve("not-a-build")).isDirectory();
    }

    @Test
    void sweep_whenTheRootDoesNotExistYet_thenNothingHappens() {
        properties.setWorkspaceDirectory(root.resolve("not-created-yet"));

        assertThat(new BuildWorkspaces(properties).sweep(Set.of())).isZero();
    }

    @Test
    void root_whenNothingConfigured_thenBelowTheTemporaryDirectory() {
        properties.setWorkspaceDirectory(null);

        assertThat(new BuildWorkspaces(properties).root().toString())
                .startsWith(System.getProperty("java.io.tmpdir"));
    }
    /**
     * The flag has to survive the next build, which is exactly the sequence someone reproducing a failure goes
     * through: fail, look, trigger again. Guarding only discard() kept the workspace until the next sweep.
     */
    @Test
    void sweep_whenWorkspacesAreKept_thenNothingIsRemoved() throws IOException {
        properties.setKeepWorkspace(true);
        workspaces.create(1);
        workspaces.create(2);

        int removed = workspaces.sweep(Set.of(2L));

        assertThat(removed).isZero();
        assertThat(root.resolve("1")).isDirectory();
        assertThat(root.resolve("2")).isDirectory();
    }

}
