package ch.admin.bit.jeap.doc.web.api.governance;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * The body of the webhook call, as the Governance Service sends it.
 * <p>
 * {@code scoredAt} is read for the log line only - the import that follows reads the Governance Service's
 * current state rather than this one run's worth of it, and does not need the timestamp to do that.
 *
 * @param eventId  identifies the scoring run, so a repeated delivery of it is ignored
 * @param scoredAt when the scoring run that is being reported completed
 */
@Schema(description = "A completed scoring run of the Governance Service")
record GovernanceWebhookDto(
        @Schema(description = "Identifies the scoring run; a repeated delivery of it is ignored",
                example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") String eventId,
        @Schema(description = "When the scoring run completed") OffsetDateTime scoredAt) {
}
