package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import ch.admin.bit.jeap.doc.domain.upload.validation.StructureValidation;
import ch.admin.bit.jeap.doc.web.api.upload.UploadBodies;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the path tree of a validation request, bounded while it is read.
 * <p>
 * <b>Both bounds are applied during the read, not after it.</b> A body that announces no length - chunked
 * encoding - would otherwise be unbounded, and a body well within the byte limit can still hold millions of
 * one-character paths, which is millions of {@code String}s in the heap of a service that is also running a
 * Docusaurus build. So the stream is cut at {@link #maxBytes} and the array is refused at the element after
 * {@code max-paths}, before either has been kept.
 * <p>
 * <b>The bounds are derived, not configured.</b> At most {@code max-paths} paths of
 * {@link StructureValidation#MAX_PATH_LENGTH} characters, plus the quoting and the commas around them: one
 * property to set wrong instead of two that have to agree.
 */
@Component
@RequiredArgsConstructor
class PathTreeReader {

    /** What one path costs in JSON beyond its characters: two quotes, a comma, and room for escaping. */
    static final int JSON_OVERHEAD_PER_PATH = 8;

    /** Room for the object around the array - the key, the brackets, whitespace a client may have added. */
    static final int ENVELOPE = 1024;

    private static final String PATHS_PROPERTY = "paths";
    private static final JsonFactory JSON = new JsonFactory();

    private final UploadProperties properties;

    /** The most bytes a list of paths of this source format could be. */
    static long maxBytes(UploadProperties properties, SourceFormat sourceFormat) {
        return (long) properties.getValidation().maxPathsOf(sourceFormat)
               * (StructureValidation.MAX_PATH_LENGTH + JSON_OVERHEAD_PER_PATH) + ENVELOPE;
    }

    /** The paths of the tree the request carries, or an empty list where its body names none. */
    List<String> read(HttpServletRequest request, SourceFormat sourceFormat) {
        try {
            return read(request.getInputStream(), sourceFormat);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The paths of the tree, or an empty list where the body names none.
     *
     * @param body the body of the request, read at most as far as the bounds allow
     */
    List<String> read(InputStream body, SourceFormat sourceFormat) {
        try (JsonParser parser =
                     JSON.createParser(UploadBodies.limitedTo(body, maxBytes(properties, sourceFormat)))) {
            return tree(parser, sourceFormat);
        } catch (JacksonException e) {
            // Not the parser's own text: what is wrong with the JSON is the caller's to see in their body, and
            // echoing an internal message tells them nothing they can act on.
            throw InvalidUploadException.bodyIsNotAPathTree();
        }
    }

    private List<String> tree(JsonParser parser, SourceFormat sourceFormat) {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw InvalidUploadException.bodyIsNotAPathTree();
        }
        List<String> paths = null;
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            if (PATHS_PROPERTY.equals(parser.currentName())) {
                paths = paths(parser, sourceFormat);
            } else {
                skipProperty(parser);
            }
        }
        return paths == null ? List.of() : paths;
    }

    private List<String> paths(JsonParser parser, SourceFormat sourceFormat) {
        if (parser.nextToken() != JsonToken.START_ARRAY) {
            throw InvalidUploadException.bodyIsNotAPathTree();
        }
        int maxPaths = properties.getValidation().maxPathsOf(sourceFormat);
        List<String> paths = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (paths.size() == maxPaths) {
                throw InvalidUploadException.tooManyPaths(maxPaths);
            }
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw InvalidUploadException.bodyIsNotAPathTree();
            }
            paths.add(parser.getString());
        }
        return paths;
    }

    /** Past the value of the property the parser stands on, whatever that value is. */
    private static void skipProperty(JsonParser parser) {
        JsonToken value = parser.nextToken();
        if (value == null) {
            throw InvalidUploadException.bodyIsNotAPathTree();
        }
        if (value.isStructStart()) {
            parser.skipChildren();
        }
    }
}
