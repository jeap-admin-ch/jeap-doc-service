package ch.admin.bit.jeap.doc.sitegenerator;

import java.util.Map;
import java.util.SequencedSet;

/**
 * What writing one part's content produced: what each environment's model contributed, and the timestamps of
 * the run that a digest of the content has to ignore.
 *
 * @param models             per environment that reads an architecture model, what it contributed. An
 *                           environment that reads none is absent rather than zero
 * @param volatileTimestamps the timestamps this run wrote into the pages, in the order they have to be
 *                           replaced in. They are what makes two runs over the same documentation
 *                           differ, so {@link ContentDigest} takes them out
 */
public record WrittenContent(Map<String, EnvironmentModel> models,
                             SequencedSet<String> volatileTimestamps) {
}
