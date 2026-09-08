package ch.admin.bit.jeap.doc.web.api.architecture;

import ch.admin.bit.jeap.doc.domain.architecture.imports.ArchitectureImportState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * What the last import of one environment and one kind did - the model, the OpenAPI specifications or the
 * database schemas.
 *
 * @param kind          which of the three this is
 * @param itemCount     how many things are stored for it
 * @param complete      whether the last run got through its whole list. An incomplete run is why the next one
 *                      does not trust the index tag it was given
 * @param lastAttemptAt when a run last started, successful or not
 * @param lastSuccessAt when a run last succeeded, null while none ever has
 * @param lastOutcome   what the last run did, null while none ever has. It is not derivable from the two
 *                      timestamps: a run that stopped at its deadline is neither a success nor a failure
 * @param failureReason why the last run failed, null if it did not
 */
@Schema(description = "What the last import of one environment and kind did")
record ImportStateDto(
        String kind,
        int itemCount,
        boolean complete,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        String lastOutcome,
        String failureReason) {

    static ImportStateDto of(ArchitectureImportState state) {
        return new ImportStateDto(state.kind().name(), state.itemCount(), state.complete(),
                state.lastAttemptAt(), state.lastSuccessAt(),
                state.lastOutcome() == null ? null : state.lastOutcome().name(), state.failureReason());
    }
}
