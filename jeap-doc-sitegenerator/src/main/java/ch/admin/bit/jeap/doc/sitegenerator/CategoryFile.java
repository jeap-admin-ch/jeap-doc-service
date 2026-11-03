package ch.admin.bit.jeap.doc.sitegenerator;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeType;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Optional;

/**
 * A folder's {@code _category_.json} in an uploaded tree, as it is published: with only what it may say.
 * <p>
 * Docusaurus reads a folder's label and position from it and <b>fails the whole build</b> on one it cannot
 * resolve - a {@code link} to a page that does not exist, a slug at a route that is taken, a key it does not know.
 * So the file is not written as it was uploaded, but rebuilt from the keys that cannot fail.
 */
@Slf4j
final class CategoryFile {

    static final String NAME = "_category_.json";

    /** What a folder's {@code _category_.json} may say, and as which JSON type. Anything else is dropped. */
    private static final Map<String, JsonNodeType> KEYS = Map.of(
            "label", JsonNodeType.STRING,
            "position", JsonNodeType.NUMBER,
            "collapsed", JsonNodeType.BOOLEAN,
            "collapsible", JsonNodeType.BOOLEAN,
            "className", JsonNodeType.STRING);

    private static final ObjectMapper JSON = new ObjectMapper();

    private CategoryFile() {
    }

    static boolean isOne(String path) {
        return path.equals(NAME) || path.endsWith("/" + NAME);
    }

    /**
     * The file with only what it may say, or nothing where it is no JSON object - the folder is then labelled by
     * its name, as one without the file is.
     *
     * @param uploaded the file as it was uploaded
     * @param path     where it is in the tree, for the log
     * @param site     the static site it was uploaded for, for the log
     */
    static Optional<String> publishable(byte[] uploaded, String path, String site) {
        JsonNode json = jsonOf(uploaded);
        if (json == null || !json.isObject()) {
            log.warn("The uploaded file {} of the static site {} is not a JSON object, and is not published.",
                    path, site);
            return Optional.empty();
        }
        ObjectNode kept = keptOf(json);
        if (kept.size() < json.size()) {
            log.info("The uploaded file {} of the static site {} says more than a folder's label, position and "
                     + "folding. Only that is published.", path, site);
        }
        return Optional.of(JSON.writeValueAsString(kept));
    }

    /** The keys that may be published, in the uploaded order, so that the file written is the same on every build. */
    private static ObjectNode keptOf(JsonNode json) {
        ObjectNode kept = JSON.createObjectNode();
        for (Map.Entry<String, JsonNode> property : json.properties()) {
            if (property.getValue().getNodeType() == KEYS.get(property.getKey())) {
                kept.set(property.getKey(), property.getValue());
            }
        }
        return kept;
    }

    private static JsonNode jsonOf(byte[] bytes) {
        try {
            return JSON.readTree(bytes);
        } catch (JacksonException e) {
            return null;
        }
    }
}
