package ch.admin.bit.jeap.doc.web.api.sites;

import ch.admin.bit.jeap.doc.domain.BuildProperties;
import ch.admin.bit.jeap.doc.domain.BuildRequestOutcome;
import ch.admin.bit.jeap.doc.domain.DocumentationBuildTrigger;
import ch.admin.bit.jeap.doc.domain.DocumentationSiteStatus;
import ch.admin.bit.jeap.doc.domain.DocumentationSites;
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
 * which sets one collapsing request per site that {@code DocumentationBuildRunner} claims under that site's
 * lock - and one build of a site at a time, exactly one follow-up run per burst of triggers and one build per
 * tick per instance all rest on there being no second path to a build. So this endpoint asks, answers
 * {@code 202} and says how long it takes until an instance looks; it never starts a build on the request thread.
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
    private final DocumentationBuildTrigger trigger;
    private final BuildProperties buildProperties;
    private final Clock clock;

    @Operation(summary = "Ask for a site to be published",
            description = "Asks for the documentation site to be generated and published. The build does not "
                          + "run on this request: it is picked up by an instance within the poll interval, and "
                          + "an ask that joins a request already pending is answered with requested=false.")
    @PostMapping(path = SiteApiPaths.BUILDS, produces = "application/json")
    @PreAuthorize(Roles.HAS_SITES_ADMIN_ROLE)
    public ResponseEntity<BuildRequestedDto> requestBuild(
            @Parameter(description = "Identifier of the site") @PathVariable String site,
            Authentication caller) {
        requireConfigured(site);
        BuildRequestOutcome outcome = trigger.requestBecauseAnOperatorAsked(site);
        log.info("A build of the documentation site {} was asked for over the API by {}; it {}.",
                site, nameOf(caller),
                outcome.created() ? "was put on the queue" : "joined a request already pending");
        return ResponseEntity.accepted()
                .body(BuildRequestedDto.of(site, outcome, buildProperties.getPollInterval()));
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
    @GetMapping(path = SiteApiPaths.BUILDS + "/{buildId}", produces = "application/json")
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
        return caller == null ? "?" : LINE_BREAK.matcher(caller.getName()).replaceAll("_");
    }
}
