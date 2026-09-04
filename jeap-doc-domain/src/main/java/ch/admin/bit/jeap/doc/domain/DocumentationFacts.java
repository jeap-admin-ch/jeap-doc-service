package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ImportOutcome;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What the doc service may say about itself in public, for one site.
 * <p>
 * <b>This record is the disclosure contract.</b> Everything the generated page and the status JSON print comes
 * from here, and nothing here is a value that must not be published - no instance name, no object prefix, no
 * bucket, no database, no upstream URL and no failure reason. Those exist on the rows this is assembled from,
 * and leaving them behind is the whole point of assembling it: the page writer cannot print what it was never
 * handed, and a value added here later is a value somebody had to add to a record whose javadoc says this.
 * <p>
 * Failure reasons are the sharpest of those. They are built from what an upstream answered, so they quote hosts,
 * paths and occasionally a token's error body. <b>Whether the last attempt failed is publishable; why it failed
 * is not.</b>
 *
 * @param service      the doc service itself, the same for every site
 * @param site         the site this describes
 * @param environments its environments, in the order the switcher shows them
 * @param schedules    when the documentation changes next
 */
// What a build cost is deliberately absent. A page cannot describe the build that writes it, and describing
// the publication before it would print numbers that are not the reader's - so the five metrics of the run
// are written beside the page as JSON, at the seam between the generator and the upload, and the page fetches
// them. See AboutThisDocumentation and DocumentationStatus.
public record DocumentationFacts(
        Service service,
        SiteFacts site,
        List<EnvironmentFacts> environments,
        Schedules schedules) {

    public DocumentationFacts {
        environments = List.copyOf(environments);
    }

    /**
     * The doc service that generated the documentation.
     *
     * @param version     the version of the doc service, or null where it cannot be read
     * @param generatedAt when this build started, which is when everything else here was read
     */
    public record Service(String version, Instant generatedAt) {
    }

    /**
     * The site, as it is configured.
     *
     * @param id                        the site id, which an upload names
     * @param title                     what the navbar and the browser tab say
     * @param templates                 the structure templates this instance generates with, by their label
     * @param architectureModelRequired whether the site waits for the architecture model before it is published
     * @param publishOnUpload           whether an upload asks for a build
     * @param retainedPublications      how many published sites are kept per site
     */
    public record SiteFacts(String id, String title, List<String> templates,
                            boolean architectureModelRequired, boolean publishOnUpload,
                            int retainedPublications) {

        public SiteFacts {
            templates = List.copyOf(templates);
        }
    }

    /**
     * One environment of the site, and what the architecture repository of that stage last gave it.
     * <p>
     * The counts and the moment the content was imported are <b>not</b> here: they are what the build has just
     * read out of the stored model, and they reach the page from the run rather than from a query of their own.
     *
     * @param id               the environment id, and the path segment its tree is served under
     * @param label            the name a reader sees
     * @param main             whether this is the tree served at the site root
     * @param latest           whether this is where the documentation of an undeployed component goes
     * @param modelConfigured  whether an architecture repository is configured for this stage. One without is
     *                         legitimate: its tree carries the root page and whatever was uploaded into it
     * @param lastImportAt     when the architecture repository was last read successfully - which is a
     *                         different question, and the one that says whether the import is still working
     * @param lastImportOutcome what the last import run did, or null where none has run
     * @param staleAfter       how old {@code lastImportAt} may be before a build says so
     */
    public record EnvironmentFacts(String id, String label, boolean main, boolean latest,
                                   boolean modelConfigured, Instant lastImportAt,
                                   ImportOutcome lastImportOutcome, Duration staleAfter) {

        /** Whether the import of this environment is behind, by the same measure the build's warning uses. */
        public boolean importIsBehind(Instant now) {
            return modelConfigured && (lastImportAt == null
                                       || Duration.between(lastImportAt, now).compareTo(staleAfter) > 0);
        }
    }

    /**
     * When the documentation changes next. A cron expression and, where it is one this service can read, the
     * moment it fires - both in the time zone of the service.
     *
     * @param publication    when the site is regenerated, or null for never on a schedule
     * @param publicationAt  the next publication, or null where there is no schedule
     * @param import_        when the architecture repository is imported, or null for never
     * @param importAt       the next import, or null where there is no schedule
     */
    public record Schedules(String publication, Instant publicationAt, String import_, Instant importAt) {
    }

}
