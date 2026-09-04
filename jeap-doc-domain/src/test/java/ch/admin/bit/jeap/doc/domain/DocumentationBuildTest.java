package ch.admin.bit.jeap.doc.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record of one run, and the two questions asked of it: how long it took, and what it becomes when the
 * instance running it turns out to be gone.
 */
class DocumentationBuildTest {

    private static final Instant STARTED = Instant.parse("2026-08-27T09:00:00Z");

    @Test
    void duration_whenTheBuildHasFinished_thenHowLongItTook() {
        DocumentationBuild finished = build(BuildState.SUCCEEDED, STARTED.plusSeconds(90));

        assertThat(finished.duration(STARTED.plusSeconds(3600))).isEqualTo(Duration.ofSeconds(90));
    }

    /**
     * A build that is still running has no end yet, so the answer has to be measured against now - which is what
     * makes "how long has this been running" answerable at all.
     */
    @Test
    void duration_whenTheBuildIsStillRunning_thenHowLongItHasBeenRunning() {
        DocumentationBuild running = build(BuildState.RUNNING, null);

        assertThat(running.duration(STARTED.plusSeconds(120))).isEqualTo(Duration.ofSeconds(120));
    }

    /**
     * The row the caller read was still RUNNING; what it is handed back has to say what is now true, or the
     * count and the state it reports would disagree with the database it just wrote.
     */
    @Test
    void abandonedAt_thenItIsAbandonedAndFinished() {
        DocumentationBuild running = build(BuildState.RUNNING, null);

        DocumentationBuild abandoned = running.abandonedAt(STARTED.plusSeconds(600));

        assertThat(abandoned.state()).isEqualTo(BuildState.ABANDONED);
        assertThat(abandoned.state().isFinished()).isTrue();
        assertThat(abandoned.finishedAt()).isEqualTo(STARTED.plusSeconds(600));
        // Everything else is the build that was, not a new one.
        assertThat(abandoned.id()).isEqualTo(running.id());
        assertThat(abandoned.site()).isEqualTo(running.site());
        assertThat(abandoned.trigger()).isEqualTo(running.trigger());
        assertThat(abandoned.startedAt()).isEqualTo(running.startedAt());
        assertThat(abandoned.instance()).isEqualTo(running.instance());
    }

    /**
     * Only a running build is unfinished. The workspace sweep and the history clean-up both go by this, so a
     * state that answered wrongly would either delete a directory in use or keep one for ever.
     */
    @Test
    void isFinished_thenOnlyARunningBuildIsNot() {
        assertThat(BuildState.RUNNING.isFinished()).isFalse();
        assertThat(BuildState.SUCCEEDED.isFinished()).isTrue();
        assertThat(BuildState.FAILED.isFinished()).isTrue();
        assertThat(BuildState.ABANDONED.isFinished()).isTrue();
        assertThat(BuildState.ABORTED.isFinished()).isTrue();
    }

    private static DocumentationBuild build(BuildState state, Instant finishedAt) {
        return new DocumentationBuild(7L, Site.DEFAULT_SITE, BuildTrigger.UPLOAD, state, STARTED, finishedAt,
                "doc-service-1", null, 0, 0, 0, null, null);
    }
}
