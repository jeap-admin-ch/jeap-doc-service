package ch.admin.bit.jeap.doc.web.api.architecture;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What became of an ask to import the architecture repository, per environment.
 *
 * @param environments   which environments will be imported, in the order they will be imported. An
 *                       environment whose import was already running is here too: this ask makes it run once
 *                       more, because the resources it is about may already have been read
 * @param alreadyAskedFor which of them were already on the queue and had not started reading, so this ask
 *                       joined that import rather than putting the same fetch behind it
 * @param refused        which of them were not queued - the import queue is full, or the instance that
 *                       answered is stopping. Nothing runs for these; the schedule imports them at its next
 *                       occurrence
 * @param durable        always {@code false}, and said rather than left to be assumed: the queue is this
 *                       instance's own, so an ask is lost if the instance stops before it runs. The schedule
 *                       imports the environment anyway at its next occurrence
 */
@Schema(description = "What became of an ask to import the architecture repository")
record ImportRequestedDto(List<String> environments, List<String> alreadyAskedFor, List<String> refused,
                          boolean durable) {

    static ImportRequestedDto of(List<String> queued, List<String> alreadyAskedFor, List<String> refused) {
        return new ImportRequestedDto(List.copyOf(queued), List.copyOf(alreadyAskedFor), List.copyOf(refused),
                false);
    }
}
