package ch.admin.bit.jeap.doc.sitegenerator;

import java.util.Collections;
import java.util.Map;
import java.util.SequencedSet;
import java.util.TreeMap;

/**
 * What writing one part's content produced: what each environment's model contributed, and the timestamps of
 * the run that a digest of the content has to ignore. Sources may also contribute site-level metadata.
 *
 * @param models             per environment that reads an architecture model, what it contributed. An
 *                           environment that reads none is absent rather than zero
 * @param volatileTimestamps the timestamps this run wrote into the pages, in the order they have to be
 *                           replaced in. They are what makes two runs over the same documentation
 *                           differ, so {@link ContentDigest} takes them out
 * @param siteMetadata       source-specific properties for site.json; must not replace common site properties.
 *                           Keep these identical across parts and omit absent features to preserve content digests
 */
public record WrittenContent(Map<String, EnvironmentModel> models,
                             SequencedSet<String> volatileTimestamps,
                             Map<String, Object> siteMetadata) {
    public WrittenContent {
        // Stable key order keeps site.json identical across JVMs and parts.
        siteMetadata = Collections.unmodifiableMap(new TreeMap<>(siteMetadata));
    }

    public WrittenContent(Map<String, EnvironmentModel> models, SequencedSet<String> volatileTimestamps) {
        this(models, volatileTimestamps, Map.of());
    }
}
