package ch.admin.bit.jeap.doc.objectstorage;

import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;

import java.util.Locale;
import java.util.Map;

/**
 * What a file of a generated site is, by its extension.
 * <p>
 * Spring's own table answers this for nearly everything a Docusaurus site emits, down to {@code .mjs} and
 * {@code .wasm}, and it is maintained where a hand-written list here would quietly drift. Only what that table
 * does not know is kept below.
 * <p>
 * Getting it wrong is not cosmetic: the type is written onto the object when it is published and is what a
 * browser is told when it is served, and a module script served as {@code application/octet-stream} is refused
 * rather than run. Anything still unknown is served as bytes, which is the answer that cannot be wrong.
 */
final class MediaTypes {

    private static final String DEFAULT_TYPE = MediaType.APPLICATION_OCTET_STREAM_VALUE;

    /**
     * What Spring's table has no entry for. Both come out of a documentation build: source maps beside every
     * bundle, and Markdown wherever a page's source is published next to it.
     */
    private static final Map<String, String> ALSO_KNOWN = Map.of(
            "map", MediaType.APPLICATION_JSON_VALUE,
            "md", "text/markdown");

    private MediaTypes() {
    }

    /**
     * What the file with the given name is. Text types carry the charset: the generated site is UTF-8
     * throughout, and a browser that guesses gets the accents wrong.
     */
    static String of(String fileName) {
        String type = MediaTypeFactory.getMediaType(fileName)
                .map(MediaType::toString)
                .orElseGet(() -> ALSO_KNOWN.getOrDefault(extensionOf(fileName), DEFAULT_TYPE));
        return needsCharset(type) ? type + ";charset=UTF-8" : type;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the bytes are text. {@code +json} and {@code +xml} are covered too, because a suffixed type is
     * still the structured text its base type is.
     */
    private static boolean needsCharset(String type) {
        return type.startsWith("text/") || type.endsWith("json") || type.endsWith("xml");
    }
}
