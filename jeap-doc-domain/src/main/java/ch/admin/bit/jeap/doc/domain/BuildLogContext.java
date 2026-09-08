package ch.admin.bit.jeap.doc.domain;

import org.slf4j.MDC;

import java.util.Map;

/**
 * The fields every log line of one build carries, so that a trace can be looked up instead of matched.
 * <p>
 * <b>Why it exists.</b> An instance builds several parts at once and the site generator's output is pumped
 * into the log under one thread name for all of them, so the thousands of lines a publication produces are
 * indistinguishable: attributing one to a build meant adding up a trace and comparing the total against the
 * {@code docusaurus_millis} of a build row. Two fields turn that into a filter.
 * <p>
 * <b>The scope has to be closed, and that is the whole risk here.</b> Builds share a thread pool, so a value
 * left behind is inherited by the next build on that thread and labels its lines with another part - which is
 * worse than no label at all. So this is an {@link AutoCloseable} used in a try-with-resources, and closing it
 * restores the context the thread had before it rather than merely removing what it put: whatever was added
 * inside the scope - the build id, once the row exists - goes with it.
 * <p>
 * <b>A thread started inside the scope does not inherit it.</b> The context is thread-local and is copied at
 * the moment a thread is created only by the JDK's inheritable locals, which the MDC is not. Whoever starts a
 * thread that logs about this build hands it {@link #current()} and sets it there - the pump that drains the
 * site generator's output is the one place that does.
 */
public final class BuildLogContext implements AutoCloseable {

    /** The site being published. */
    public static final String SITE = "docSite";

    /** The part of it: one system, or the shell. */
    public static final String PART = "docPart";

    /** The build these lines belong to, which is also what the build record is keyed by. */
    public static final String BUILD_ID = "docBuildId";

    /** What the thread's context was before this scope, restored by {@link #close()}. Null for none. */
    private final Map<String, String> before;

    private BuildLogContext(Map<String, String> before) {
        this.before = before;
    }

    /**
     * Opens the scope for the work on one part. The build id is not known yet - the row is written once the
     * request has been claimed - and is added by {@link #buildIs} inside this scope.
     */
    public static BuildLogContext of(PartKey part) {
        BuildLogContext context = new BuildLogContext(MDC.getCopyOfContextMap());
        MDC.put(SITE, part.site());
        MDC.put(PART, part.part());
        return context;
    }

    /**
     * Names the build the lines from here on belong to. It is undone by the enclosing scope's
     * {@link #close()}, which is why it may be a plain static: there is no state of its own to keep.
     */
    public static void buildIs(long buildId) {
        MDC.put(BUILD_ID, Long.toString(buildId));
    }

    /**
     * The context of the calling thread, to be handed to a thread that logs about the same build. Null where
     * the caller has none, which is what {@link MDC#getCopyOfContextMap()} answers.
     */
    public static Map<String, String> current() {
        return MDC.getCopyOfContextMap();
    }

    /** Sets a context handed over by {@link #current()}, on a thread that has just started. */
    public static void adopt(Map<String, String> context) {
        if (context != null) {
            MDC.setContextMap(context);
        }
    }

    @Override
    public void close() {
        if (before == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(before);
        }
    }
}
