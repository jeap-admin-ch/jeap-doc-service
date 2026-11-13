package ch.admin.bit.jeap.doc.web.uiapi;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A JSON answer of the UI API with a <b>weak</b> entity tag over its body, revalidated on every use.
 * <p>
 * Weak, like the files of the sites: the container does not compress a response that carries a strong tag, and
 * nothing reads these answers in byte ranges, so the only thing a strong one would add is the refusal to
 * compress. Revalidation works the same either way. {@code no-cache} rather than {@code no-store}: a browser
 * may keep the answer, but has to ask whether it is still current before showing it.
 * <p>
 * The {@code 304} is Spring's: it compares {@code If-None-Match} with the tag of a {@code 200} it is handed for
 * a {@code GET} or {@code HEAD}, and answers without the body where they match.
 */
public final class EntityTagged {

    private EntityTagged() {
    }

    public static ResponseEntity<byte[]> json(byte[] body) {
        return ResponseEntity.ok()
                .eTag("W/\"sha256:" + sha256(body) + "\"")
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
