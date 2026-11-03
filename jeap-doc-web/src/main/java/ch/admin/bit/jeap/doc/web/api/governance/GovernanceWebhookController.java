package ch.admin.bit.jeap.doc.web.api.governance;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceWebhook;
import ch.admin.bit.jeap.doc.web.api.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

/**
 * Receives the Governance Service's notification that a scoring run completed.
 * <p>
 * <b>Answers before importing.</b> The import is handed to {@code GovernanceImportPickup} and runs on its own
 * thread; this request never waits for a fetch of the Governance Service, which the caller's scoring
 * transaction is already outside of on the other side.
 */
@Slf4j
@RestController
@Tag(name = "governance", description = "The Governance Service's webhook")
@RequiredArgsConstructor
class GovernanceWebhookController {

    private static final Pattern LINE_BREAK = Pattern.compile("[\\r\\n]");

    private final GovernanceWebhook webhook;

    @Operation(summary = "Report a completed scoring run",
            description = "Answers 202 at once and imports the governance data in the background - the whole "
                          + "of it, since the resources are few and their entity tags skip what did not "
                          + "change. A repeated event ID is ignored. An import already running absorbs the "
                          + "call rather than starting a second one beside it. While another instance is "
                          + "importing, the import is tried again until this instance has run it.")
    @PostMapping(path = GovernanceApiPaths.WEBHOOK, consumes = "application/json")
    @PreAuthorize(Roles.HAS_GOVERNANCE_NOTIFICATIONS_WRITE_ROLE)
    public ResponseEntity<Void> notifyOfAScoringRun(@RequestBody GovernanceWebhookDto body) {
        String eventId = requireAnEventId(body);
        boolean accepted = webhook.notifyOfAScoringRun(eventId);
        log.info("The governance webhook reported the scoring run {} (scored at {}): {}.", eventId,
                body.scoredAt(), accepted ? "importing" : "already accepted, ignored");
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private static String requireAnEventId(GovernanceWebhookDto body) {
        if (body == null || body.eventId() == null || body.eventId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventId is required.");
        }
        return LINE_BREAK.matcher(body.eventId()).replaceAll("_");
    }
}
