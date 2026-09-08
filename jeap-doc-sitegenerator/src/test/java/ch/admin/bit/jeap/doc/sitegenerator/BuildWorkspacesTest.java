package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
    void deleteTree_whenTheTreeIsAlreadyGone_thenItIsNotAFailure() {
        assertThatCode(() -> BuildWorkspaces.deleteTree(root.resolve("never-there")))
                .doesNotThrowAnyException();
    }

    @Test
    void deleteTree_thenTheWholeTreeIsGone() throws IOException {
        Path tree = deepTree(root.resolve("42"));

        BuildWorkspaces.deleteTree(tree);

        assertThat(tree).doesNotExist();
    }

    /**
     * The failure this method exists for: two things removing one workspace at the same time - a build
     * discarding its own while a sweep walks it. A recursive delete that reports what another walk has
     * already removed logged a stack trace for every workspace it swept; this one reports what it cannot
     * remove and nothing else.
     */
    @Test
    void deleteTree_whenTwoOfThemWalkTheSameTree_thenNeitherOfThemFails() throws Exception {
        Path tree = deepTree(root.resolve("42"));
        ExecutorService both = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = both.submit(deleting(tree));
            Future<?> another = both.submit(deleting(tree));

            assertThatCode(() -> one.get(30, TimeUnit.SECONDS)).doesNotThrowAnyException();
            assertThatCode(() -> another.get(30, TimeUnit.SECONDS)).doesNotThrowAnyException();
        } finally {
            both.shutdownNow();
        }

        assertThat(tree).doesNotExist();
    }

    private static Callable<Void> deleting(Path tree) {
        return () -> {
            BuildWorkspaces.deleteTree(tree);
            return null;
        };
    }

    /**
     * A tree of the shape a workspace has - the content of one part is a directory per environment, chapter
     * and component - so that two walks over it really overlap rather than finishing on the first entry.
     */
    private static Path deepTree(Path tree) throws IOException {
        for (int environment = 0; environment < 4; environment++) {
            for (int chapter = 0; chapter < 12; chapter++) {
                for (int component = 0; component < 6; component++) {
                    Path directory = tree.resolve("content/env-" + environment + "/chapter-" + chapter
                                                  + "/component-" + component);
                    Files.createDirectories(directory);
                    Files.writeString(directory.resolve("index.md"), "# a page");
                }
            }
        }
        return tree;
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
