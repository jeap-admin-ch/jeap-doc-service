package ch.admin.bit.jeap.doc.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The defaults of a build that were chosen by measuring rather than by taste, and that an instance therefore
 * inherits without saying anything.
 */
class BuildPropertiesTest {

    /**
     * A hundred pages per static-generation task, and deliberately not the site generator's own ten: at ten
     * the pool waits on the thread that hands the chunks out, and the static generation of a large part takes
     * more than twice as long. Above a hundred a part of a few hundred pages has fewer tasks than the pool has
     * workers.
     */
    @Test
    void ssgTaskSize_isAHundredPagesRatherThanTheGeneratorsOwnTen() {
        assertThat(new BuildProperties().getSsgTaskSize()).isEqualTo(100);
    }

    /** The pool itself stays off by default: a worker thread is a V8 isolate with a heap of its own. */
    @Test
    void ssgWorkerThreads_isOff() {
        assertThat(new BuildProperties().isSsgWorkerThreads()).isFalse();
    }
}
