package ch.admin.bit.jeap.doc.web.site;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one script the doc service adds to every HTML page of a microsite.
 * <p>
 * A microsite is served with an opaque origin, and a document with one may not touch storage or cookies: an
 * application that reads either while it loads never starts. The shim gives both back, in memory and for as
 * long as the page is open, so the isolation is unchanged - see {@code docs/custom-documentation.md}, which
 * says so to the teams whose pages this rewrites.
 * <p>
 * <b>The version is the digest of the script</b>, not a number somebody has to remember to raise. It is part
 * of the entity tag of every page served with the shim, so changing the script invalidates what readers hold.
 */
@Component
class MicrositeShim {

    /** Marks the tag as this service's, so a page that already carries it is not given a second one. */
    static final String MARKER = "data-jeap-doc-microsite-shim";

    private static final String RESOURCE = "microsite/shim.js";
    private static final int VERSION_LENGTH = 12;

    private final byte[] tag;
    private final String version;

    MicrositeShim() {
        String script = read();
        this.tag = ("<script " + MARKER + ">" + script + "</script>").getBytes(StandardCharsets.UTF_8);
        this.version = digestOf(script);
    }

    /** The script tag, as bytes to be written into a page. */
    byte[] tag() {
        return tag.clone();
    }

    /** How many bytes the tag adds to a document. */
    int length() {
        return tag.length;
    }

    /** What identifies this version of the shim in an entity tag. */
    String version() {
        return version;
    }

    private static String read() {
        try (InputStream script = new ClassPathResource(RESOURCE).getInputStream()) {
            return new String(script.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The service cannot serve a microsite without it, and finding that out per request would be a
            // microsite that half works.
            throw new UncheckedIOException("The microsite shim %s is not on the classpath.".formatted(RESOURCE), e);
        }
    }

    private static String digestOf(String script) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, VERSION_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }
}
