package ch.admin.bit.jeap.doc.domain;

import java.util.List;
import java.util.Optional;

/**
 * How a documentation site is cut into parts.
 * <p>
 * This is the one place that decides the axis of the split, and everything else - the builds, the
 * publications, the locks, the requests, the serving - works on whatever it answers. Re-cutting a site is
 * therefore a different implementation of this and nothing else.
 * <p>
 * It is a plugin point rather than a driven port: there are as many implementations as there are axes, and the
 * instance is configured with one.
 */
public interface SitePartition {

    /**
     * What this partition cuts on, for the log line and the administration API - {@code system},
     * {@code environment}.
     */
    String axis();

    /**
     * Every part of the given site, the shell first.
     * <p>
     * <b>It reads the architecture model</b> - a part per system exists because the model says the system
     * does - so it costs one indexed query per environment of the site and nothing is memoized. That is
     * affordable on a request thread, which is what the administration API does; it is not affordable per
     * page, which is why the generator asks once per build and passes the answer down. Serving a path asks
     * {@link #partOf} instead, which reads no model at all.
     */
    List<SitePart> partsOf(Site site);

    /**
     * The part with the given identifier, <b>without reading the architecture model</b>.
     * <p>
     * What a part owns follows from its identifier, so this can answer while serving a request - which is what
     * resolves a path to the publication that holds it. Empty when the identifier is not one this partition
     * would ever produce.
     */
    Optional<SitePart> partOf(Site site, String partId);

    /** The part that owns the site's own pages and everything no other part claims. */
    SitePart shellOf(Site site);


    /**
     * The parts that carry the documentation of one system.
     * <p>
     * It is what turns a trigger that names a thing into a trigger that names a part: an upload names a system
     * and a component, and an import knows which systems of an environment moved.
     *
     * @param environment the environment the change is in, or <b>null when it is not known</b> - an upload
     *                    names no environment at all
     * @param systemName  the system as the upstream and the pipelines spell it, not its slug
     */
    List<SitePart> partsDocumenting(Site site, String environment, String systemName);
}
