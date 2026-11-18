package ch.admin.bit.jeap.doc.web.uiapi.excerpts;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceExcerpts;
import ch.admin.bit.jeap.doc.web.uiapi.EntityTagged;
import ch.admin.bit.jeap.doc.web.uiapi.UiApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The governance excerpt a system's and a component's architecture page show, read in the reader's browser.
 * <p>
 * Open, like the sites it is shown on, and answered from what the governance import stored - see
 * {@link GovernanceExcerpts}. A component page reads its system's answer and shows its own entry: a system has
 * few components, and one resource per system is one entity tag and one cache entry for all of its pages.
 */
@RestController
@Tag(name = "ui-api", description = "What the pages of the documentation sites read in the browser - open, read-only")
@RequiredArgsConstructor
class GovernanceExcerptController {

    private final GovernanceExcerpts excerpts;
    private final JsonMapper json;

    /**
     * <b>{@code 204} where there is nothing to show</b> rather than a {@code 404}: every generated system and
     * component page asks, on every instance - one without a governance site among them - and a browser reports
     * every {@code 404} of a fetch as a resource that failed to load. Nothing being scored is not an error.
     */
    @Operation(summary = "The governance excerpt of a system",
            description = "The system's score and trend, the last 14 days of its history, and per component its "
                          + "score and the rules it fails, with links into the governance site. Answered from the "
                          + "governance data this service imported, never from the Governance Service. 204 without "
                          + "a body where no governance site is configured, nothing is imported yet, or the system "
                          + "is not scored or not named. A weak entity tag, no-cache, and 304 for a matching "
                          + "If-None-Match.")
    @GetMapping(UiApiPaths.GOVERNANCE_EXCERPT)
    public ResponseEntity<byte[]> governanceExcerpt(@RequestParam(required = false) String system,
                                                    HttpServletRequest request) {
        return excerpts.ofSystem(system)
                .map(excerpt -> EntityTagged.json(
                        json.writeValueAsBytes(GovernanceExcerptDto.of(excerpt, request.getContextPath()))))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noCache()).build());
    }
}
