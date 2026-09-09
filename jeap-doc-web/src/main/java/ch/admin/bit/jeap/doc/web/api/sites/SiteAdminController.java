package ch.admin.bit.jeap.doc.web.api.sites;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.BuildRequestOutcome;
import ch.admin.bit.jeap.doc.domain.DepartedParts;
import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationParts;
import ch.admin.bit.jeap.doc.domain.DocumentationSiteStatus;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.web.api.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Administers the documentation sites: asks for one to be published, and reports what the generator has been
 * doing.
 * <p>
 * <b>Asking is not building.</b> Every trigger in the service goes through {@link DocumentationBuildTrigger},
 * which sets one collapsing request per <b>part</b> that {@code DocumentationBuildRunner} claims under that
 * part's own lock - and one build of a part at a time, exactly one follow-up run per burst of triggers and the
 * bound on how many parts an instance builds at once all rest on there being no second path to a build. So this
 * endpoint asks, answers {@code 202} and says how long it takes until an instance looks; it never starts a
 * build on the request thread.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "sites", description = "Administration of the documentation sites")
class SiteAdminController {

    /**
     * How many builds a history answers with when the caller says nothing, and the most it ever answers with.
     * A limit outside that is brought into it rather than refused: it is an operator reading a history, and the
     * useful answer to <i>give me all of them</i> is the newest hundred.
     */
    static final int DEFAULT_HISTORY_LIMIT = 20;
    static final int MAX_HISTORY_LIMIT = 100;

    private static final Pattern LINE_BREAK = Pattern.compile("[\\r\\n]");

    private final DocumentationSites sites;
    private final DocumentationSiteStatus status;
    private final DocumentationParts parts;
    private final DocumentationBuildTrigger trigger;
    private final DepartedParts departedParts;
    private final BuildProperties buildProperties;
    private final Clock clock;

    @Operation(summary = "Ask for a site to be published",
            description = "Asks for every part of the site to be generated and published, whether its content "
                          + "has moved or not - which is what to use after changing the site template. "
                          + "Nothing runs on this request: the builds are picked up by an instance within the "
                          + "poll interval.")
    @PostMapping(path = SiteApiPaths.BUILDS, produces = "application/json")
    @PreAuthorize(Roles.HAS_SITES_ADMIN_ROLE)
    public ResponseEntity<PublicationRequestedDto> requestBuild(
            @Parameter(description = "Identifier of the site") @PathVariable String site,
            Authentication caller) {
        requireConfigured(site);
        List<SitePart> asked = trigger.requestEveryPart(site);
        log.info("Every part of the documentation site {} was asked for over the API by {}: {} part(s).",
                site, nameOf(caller), asked.size());
        return ResponseEntity.accepted()
                .body(PublicationRequestedDto.of(site, asked.size(), buildProperties.getPollInterval()));
    }

    @Operation(summary = "Ask for one part of a site to be published",
            description = "Asks for one part of the documentation site to be generated and published, whether "
                          + "its content has moved or not. The build does not run on this request: it is "
                          + "picked up by an instance within the poll interval, and an ask that joins a "
                          + "request already pending is answered with requested=false.")
    @PostMapping(path = SiteApiPaths.PART_BUILDS, produces = "application/json")
    @PreAuthorize(Roles.HAS_SITES_ADMIN_ROLE)
    public ResponseEntity<BuildRequestedDto> requestPartBuild(
            @Parameter(description = "Identifier of the site") @PathVariable String site,
            @Parameter(description = "Identifier of the part") @PathVariable String part,
            Authentication caller) {
        requireConfigured(site);
        SitePart configured = parts.of(site, part)
                .map(DocumentationParts.PartState::part)
                .orElseThrow(() -> unknownPart(site, part));
        BuildRequestOutcome outcome = trigger.requestBecauseAnOperatorAsked(configured.key());
        log.info("A build of {} was asked for over the API by {}; it {}.", configured.key(), nameOf(caller),
                outcome.created() ? "was put on the queue" : "joined a request already pending");
        return ResponseEntity.accepted()
                .body(BuildRequestedDto.of(configured.key(), outcome, buildProperties.getPollInterval()));
    }

    @Operation(summary = "Remove a part the site no longer has",
            description = "Removes what a part that is no longer produced by this site's partition is still "
                          + "publishing: its objects and its build records. It is what takes a decommissioned "
                          + "system's documentation off the site today rather than after "
                          + "jeap.doc.build.departed-part-retention, which is when the nightly clean-up would "
                          + "do it. A part the site still has is refused: this never removes live "
                          + "documentation.")
    @DeleteMapping(path = SiteApiPaths.PART, produces = "application/json")
    @PreAuthorize(Roles.HAS_SITES_ADMIN_ROLE)
    public ResponseEntity<Void> removePart(
            @Parameter(description = "Identifier of the site") @PathVariable String site,
            @Parameter(description = "Identifier of the part") @PathVariable String part,
            Authentication caller) {
        Site configured = sites.find(site).orElseThrow(() -> unknownSite(site));
        DepartedParts.Removal removal = departedParts.removeNow(configured, part);
        return switch (removal) {
            case REMOVED -> {
                log.info("The part {} of the documentation site {}, which that site no longer has, was removed "
                         + "over the API by {}.", withoutLineBreaks(part), site, nameOf(caller));
                yield ResponseEntity.noContent().build();
            }
            // 409 and not 403: what refuses it is the state of the site rather than the caller's grants, and an
            // operator has to be able to tell "you may not" from "this system is still in the model".
            case STILL_A_PART -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    ("The documentation site %s still has the part %s, so it is not removed. Only a part the "
                     + "site no longer produces can be.").formatted(site, part));
            case NOTHING_TO_REMOVE -> throw unknownPart(site, part);
            // 503 and not 500: the ask was right and nothing was deleted, so repeating it is what to do. What
            // gets here is an object storage that would not delete, a build holding the part's lock, or a part
            // that was published again in between - and the records are deliberately kept in all three, or
            // nothing would name the objects that are still there.
            case NOT_REMOVED -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    ("What the part %s of the documentation site %s published could not be removed now, and "
                     + "nothing was deleted. A build may be holding it; ask again.").formatted(part, site));
        };
    }

    @Operation(summary = "Read the parts of a site",
            description = "Answers the parts the documentation site is published as: what each carries, what "
                          + "is published for it and whether it is owed a build. The age of the oldest part is "
                          + "what says whether a part has quietly stopped being rebuilt.")
    @GetMapping(path = SiteApiPaths.PARTS, produces = "application/json")
    @PreAuthorize(Roles.HAS_SITES_READ_ROLE)
    public List<PartDto> parts(@Parameter(description = "Identifier of the site") @PathVariable String site) {
        Instant now = clock.instant();
        return parts.of(site).orElseThrow(() -> unknownSite(site)).stream()
                .map(state -> PartDto.of(state.part(), state.published(), state.owedABuild(), now))
                .toList();
    }

    @Operation(summary = "Read the builds of one part of a site")
    @GetMapping(path = SiteApiPaths.PART_BUILDS, produces = "application/json")
    @PreAuthorize(Roles.HAS_SITES_READ_ROLE)
    public List<BuildDto> partBuilds(
            @Parameter(description = "Identifier of the site") @PathVariable String site,
            @Parameter(description = "Identifier of the part") @PathVariable String part,
            @Parameter(description = "How many builds to answer with, at most 100")
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_HISTORY_LIMIT) int limit) {
        requireConfigured(site);
        SitePart configured = parts.of(site, part)
                .map(DocumentationParts.PartState::part)
                .orElseThrow(() -> unknownPart(site, part));
        Instant now = clock.instant();
        return parts.recentBuildsOf(configured.key(), Math.clamp(limit, 1, MAX_HISTORY_LIMIT)).stream()
                .map(build -> BuildDto.of(build, now))
                .toList();
    }


    @Operation(summary = "Read the state of every site",
            description = "Answers what each documentation site is configured to do and what has actually "
                          + "happened to it - what is pending, what is running, what is published.")
    @GetMapping(path = SiteApiPaths.SITES, produces = "application/json")
    @PreAuthorize(Roles.HAS_SITES_READ_ROLE)
    public List<SiteStatusDto> sites() {
        Instant now = clock.instant();
        return status.all().stream().map(siteStatus -> SiteStatusDto.of(siteStatus, now)).toList();
    }

    @Operation(summary = "Read the state of one site")
    @GetMapping(path = SiteApiPaths.SITE, produces = "application/json")
    @PreAuthorize(Roles.HAS_SITES_READ_ROLE)
    public SiteStatusDto site(@Parameter(description = "Identifier of the site") @PathVariable String site) {
        return status.of(site)
                .map(siteStatus -> SiteStatusDto.of(siteStatus, clock.instant()))
                .orElseThrow(() -> unknownSite(site));
    }

    @Operation(summary = "Read the builds of a site",
            description = "Answers the most recent runs of the documentation generator for this site, newest "
                          + "first.")
    @GetMapping(path = SiteApiPaths.BUILDS, produces = "application/json")
    @PreAuthorize(Roles.HAS_SITES_READ_ROLE)
    public List<BuildDto> builds(
            @Parameter(description = "Identifier of the site") @PathVariable String site,
            @Parameter(description = "How many builds to answer with, at most 100")
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_HISTORY_LIMIT) int limit) {
        Instant now = clock.instant();
        requireConfigured(site);
        return status.recentBuilds(site, Math.clamp(limit, 1, MAX_HISTORY_LIMIT)).stream()
                .map(build -> BuildDto.of(build, now))
                .toList();
    }

    @Operation(summary = "Read one build of a site")
    @GetMapping(path = SiteApiPaths.BUILD, produces = "application/json")
    @PreAuthorize(Roles.HAS_SITES_READ_ROLE)
    public BuildDto build(
            @Parameter(description = "Identifier of the site") @PathVariable String site,
            @Parameter(description = "Identifier of the build") @PathVariable long buildId) {
        requireConfigured(site);
        return status.build(site, buildId)
                .map(build -> BuildDto.of(build, clock.instant()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "The site %s has no build %d.".formatted(site, buildId)));
    }

    /**
     * Refuses a site this instance does not configure with a 404 naming the ones it does - a site is
     * configuration, so asking for one that is not there is a typo in the request rather than something that
     * might appear later. Without it, a history would answer an empty list for a site that will never exist.
     */
    private void requireConfigured(String site) {
        if (sites.find(site).isEmpty()) {
            throw unknownSite(site);
        }
    }

    private ResponseStatusException unknownPart(String site, String part) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "The documentation site %s has no part %s.".formatted(site, part));
    }

    private ResponseStatusException unknownSite(String site) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
                "This instance does not configure a documentation site %s. It configures %s."
                        .formatted(site, sites.ids()));
    }

    /**
     * Who asked, for the audit trail the build record itself cannot hold: the trigger of a build says that
     * somebody asked, and this line says who. The name comes from a validated token, and is stripped of line
     * breaks all the same - a log entry may never be made to look like two.
     */
    private static String nameOf(Authentication caller) {
        return caller == null ? "?" : withoutLineBreaks(caller.getName());
    }

    /** A log entry may never be made to look like two, whatever a path or a token carried. */
    private static String withoutLineBreaks(String value) {
        return value == null ? "?" : LINE_BREAK.matcher(value).replaceAll("_");
    }
}
