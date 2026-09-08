package ch.admin.bit.jeap.doc.web.api.architecture;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What became of an ask to import the architecture repository.
 *
 * @param environments which environments were put on the import queue, in the order they will be imported
 * @param durable      always {@code false}, and said rather than left to be assumed: the queue is this
 *                     instance's own, so an ask is lost if the instance stops before it runs. The schedule
 *                     imports the environment anyway at its next occurrence
 */
@Schema(description = "What became of an ask to import the architecture repository")
record ImportRequestedDto(List<String> environments, boolean durable) {

    static ImportRequestedDto of(List<String> environments) {
        return new ImportRequestedDto(List.copyOf(environments), false);
    }
}
