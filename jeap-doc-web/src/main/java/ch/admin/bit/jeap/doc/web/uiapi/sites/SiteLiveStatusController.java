package ch.admin.bit.jeap.doc.web.uiapi.sites;

import ch.admin.bit.jeap.doc.domain.DocumentationProvenance;
import ch.admin.bit.jeap.doc.web.uiapi.UiApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The live status of one documentation site, which its About page fetches: answered from the service's own
 * records instead of from what a build published.
 * <p>
 * The statements it carries are the ones that page used to print, so it is as open as the page - and what may be
 * published is decided in {@link DocumentationProvenance}, as it is for the page itself.
 */
@RestController
@Tag(name = "ui-api", description = "What the pages of the documentation sites read in the browser - open, read-only")
@RequiredArgsConstructor
class SiteLiveStatusController {

    private final DocumentationProvenance provenance;

    /**
     * <b>Never cached.</b> The whole reason this is not part of the published site is that it changes without the
     * documentation changing, and a cached copy would be the frozen page again with an extra step.
     */
    @Operation(summary = "The live status of a documentation site",
            description = "When each environment's architecture model was last read, and when each schedule the "
                          + "site's About page tabulates fires next. Never cached; 404 for a site that is not "
                          + "configured.")
    @GetMapping(UiApiPaths.LIVE_STATUS)
    public ResponseEntity<?> liveStatus(@PathVariable String site) {
        return provenance.liveStatusOf(site)
                .<ResponseEntity<?>>map(status -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(status))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                                "No documentation site of this name is configured.")));
    }

}
