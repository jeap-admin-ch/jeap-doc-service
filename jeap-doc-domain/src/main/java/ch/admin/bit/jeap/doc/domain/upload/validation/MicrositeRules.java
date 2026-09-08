package ch.admin.bit.jeap.doc.domain.upload.validation;

import java.util.Set;

/**
 * What an HTML upload may carry, and what it has to carry.
 * <p>
 * <b>One list in the domain rather than a method of a template</b>, because an HTML microsite follows no
 * template: it is published as it is, in an iframe, and a per-template copy of this list would be twelve
 * identical answers. Which is also why none of the chapter rules reaches it.
 */
public final class MicrositeRules {

    /** The page an iframe opens. A microsite without one has nothing to show. */
    public static final String ENTRY_POINT = "index.html";

    /**
     * The assets a built microsite is made of.
     * <p>
     * <b>{@code js} is on it deliberately.</b> Asciidoctor output, Spring REST Docs and a Swagger UI bundle
     * all ship JavaScript, and refusing it would refuse the uploads this enabler exists to carry. What
     * contains it is the iframe and the content security policy of the publication, not this list - and it is
     * the one entry here that is a security decision rather than a practical one.
     */
    private static final Set<String> ALLOWED_FILE_EXTENSIONS = Set.of(
            "html", "htm", "css", "js", "map",
            "json", "txt", "xml",
            "png", "svg", "jpg", "jpeg", "gif", "webp", "avif", "ico",
            "woff", "woff2", "ttf", "otf",
            "pdf", "webmanifest");

    private MicrositeRules() {
    }

    public static Set<String> allowedFileExtensions() {
        return ALLOWED_FILE_EXTENSIONS;
    }

    /**
     * Whether an extension belongs in a microsite. {@code md} and {@code mdx} do not: uploaded Markdown
     * belongs in a Markdown upload, where the template's own rules reach it.
     */
    public static boolean allows(String extension) {
        return extension != null && ALLOWED_FILE_EXTENSIONS.contains(extension);
    }
}
