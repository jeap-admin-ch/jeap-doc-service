package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ImportOutcome;

import java.time.Instant;
import java.util.List;

/**
 * The state of one site's imports and schedules <b>right now</b>, served beside the site rather than written
 * into it.
 * <p>
 * <b>Why it is not on the page.</b> The page describing the documentation carries two kinds of statement. Its
 * provenance - this content was imported then, this build wrote it - stays true when the page is not rebuilt:
 * it says <i>as of then</i>, which is what it is. Its status - when the architecture repository was last read,
 * whether the import is behind, when the schedule fires next - does not: a part whose content has not moved is
 * not built again, so a frozen page would keep claiming a read that never happened and would print a next
 * occurrence that is in the past. Hiding those from the content digest instead would freeze the page that is
 * meant to report a broken import, which is the one page that has to keep working when one breaks.
 * <p>
 * So the status is taken out of the content, answered live by the service, and filled into the page's cells by
 * a client module of the site template. What the page keeps is its provenance.
 * <p>
 * <b>Everything here is publishable</b>, by the same rule {@link DocumentationFacts} follows and for the same
 * reason: the file is served to anyone who can read the site. Whether an import failed is publishable; why it
 * failed is not.
 *
 * @param site         the site this describes
 * @param at           when it was answered, which is what the judgements below were measured against
 * @param environments one entry per environment of the site, in the order the page tabulates them
 * @param schedules    the schedules the page tabulates, with when each fires next
 */
public record DocumentationLiveStatus(String site, Instant at, List<EnvironmentStatus> environments,
                                      List<ScheduleStatus> schedules) {

    /**
     * Where it is served, relative to the base URL of a site - beside {@code about-this-documentation.json},
     * which carries the numbers of one publication and is a file the publication wrote.
     * <p>
     * The name is written into the page as an absolute link, and the client module takes the URL from that
     * link, so the two cannot disagree. It is a path of the site and is therefore covered by the site's own
     * filter chain, which is open to every reader - the administration API under {@code /api} is a different
     * resource with a different rule.
     */
    public static final String FILE_NAME = "live-status.json";

    public DocumentationLiveStatus {
        environments = List.copyOf(environments);
        schedules = List.copyOf(schedules);
    }

    /**
     * What is known about one environment's import right now.
     *
     * @param id              the environment id, which is what the page's row is keyed by
     * @param modelConfigured whether an architecture repository is configured for this stage. One without is
     *                        legitimate, and has nothing to report
     * @param lastReadAt      when the architecture repository was last read successfully, or null if never
     * @param lastOutcome     what the latest run did, or null where none has run
     * @param behind          whether the import is behind, by the measure a build's own warning uses
     * @param lastRead        the three above as the page's cell reads them, so that the wording of a
     *                        generated page stays in one place rather than being rebuilt in JavaScript
     */
    public record EnvironmentStatus(String id, boolean modelConfigured, Instant lastReadAt,
                                    ImportOutcome lastOutcome, boolean behind, String lastRead) {
    }

    /**
     * One schedule of the site, and when it fires next.
     *
     * @param cron   the cron expression, which is what the page's row is keyed by - it is printed in the row
     *               beside the cell this fills
     * @param nextAt when it fires next
     * @param next   that moment as the cell reads it, spelled out relative to {@link #at}
     */
    public record ScheduleStatus(String cron, Instant nextAt, String next) {
    }
}
