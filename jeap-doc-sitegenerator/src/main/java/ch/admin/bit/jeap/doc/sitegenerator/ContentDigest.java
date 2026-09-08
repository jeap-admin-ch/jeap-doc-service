package ch.admin.bit.jeap.doc.sitegenerator;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.SequencedSet;
import java.util.stream.Stream;

/**
 * What a part's content hashes to, which is what decides whether the site generator runs at all.
 * <p>
 * Taken over the <b>content</b> and not over the inputs it was written from, so it is correct by construction:
 * whatever a page is made of - the model, a replicated artifact, an uploaded document, a generator that writes
 * something differently - it is in the page. Nobody has to maintain a list of what a part depends on.
 * <p>
 * <b>The timestamps of the run are taken out first.</b> Every generated page carries when it was built and
 * which import its content came from, so a digest over the bytes as written would differ on every run and
 * nothing would ever be skipped. The two values are known to the run that wrote them, so they are replaced by a
 * constant before hashing - which is why the caller hands them over rather than a pattern being guessed at
 * here.
 * <p>
 * What follows from that, and is worth knowing: a part that is <b>not</b> rebuilt keeps the timestamps of the
 * build that last changed it. That is the truthful reading - <i>this content is as of then</i> - and it is more
 * use than a page which claims to have been built five minutes ago and says exactly what it said last month.
 */
@Slf4j
final class ContentDigest {

    /** What a timestamp is replaced by before hashing. Anything constant does; this says why it is there. */
    private static final String TIMESTAMP = "<when-this-run-happened>";

    /**
     * What separates a path from the content that follows it, and one file from the next. A path cannot
     * contain a zero byte, so nothing can move across the boundary: without it a name ending in one more
     * character and content starting with one fewer would hash alike.
     */
    private static final byte FIELD_END = 0;

    private ContentDigest() {
    }

    /**
     * The digest of everything written under the given directory.
     *
     * @param contentDirectory   what the generator wrote
     * @param volatileTimestamps the timestamps of this run, which are replaced before hashing. <b>Their order
     *                           is part of the contract</b>: they are replaced one after the other, so a value
     *                           contained in another one has to come after it or the longer one would no
     *                           longer be there to find
     * @param generatorVersion   the version of the doc service, so that a new one rebuilds every part. <b>A
     *                           {@code SNAPSHOT} version does not change between two builds of a developer
     *                           machine</b>, so a change to the site template is not noticed there - ask for
     *                           the build with {@code force} while working on it
     */
    static String of(Path contentDirectory, SequencedSet<String> volatileTimestamps,
                     String generatorVersion) {
        MessageDigest digest = sha256();
        digest.update(bytesOf("generator:" + generatorVersion));
        // Delimited like every other field: a version ending in one more character and a first path starting
        // with one fewer would otherwise hash alike.
        digest.update(FIELD_END);
        for (Path file : filesOf(contentDirectory)) {
            // The path as well as the content: a page that moved is a site that changed, and two files whose
            // contents were swapped would otherwise hash to what they did before.
            digest.update(bytesOf(contentDirectory.relativize(file).toString().replace('\\', '/')));
            digest.update(FIELD_END);
            digest.update(canonicalBytesOf(file, volatileTimestamps));
            digest.update(FIELD_END);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Every file of the content, in one order.
     * <p>
     * Sorted by path, and not in the order the filesystem lists them: two runs that wrote the same content
     * must hash to the same value, and a directory listing is not promised to be stable.
     */
    private static List<Path> filesOf(Path contentDirectory) {
        if (!Files.isDirectory(contentDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(contentDirectory)) {
            return files.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException | UncheckedIOException e) {
            throw new UncheckedIOException("The content under " + contentDirectory + " could not be read to "
                                           + "hash it.", asIoException(e));
        }
    }

    private static String withoutTimestamps(String text, SequencedSet<String> volatileTimestamps) {
        String canonical = text;
        for (String timestamp : volatileTimestamps) {
            if (timestamp != null && !timestamp.isBlank()) {
                canonical = canonical.replace(timestamp, TIMESTAMP);
            }
        }
        return canonical;
    }

    /**
     * What of one file goes into the digest: its bytes, with the timestamps of the run taken out where it is
     * text. A file that is not UTF-8 is hashed as it is - see the note on this class.
     */
    private static byte[] canonicalBytesOf(Path file, SequencedSet<String> volatileTimestamps) {
        byte[] bytes = read(file);
        String text = asUtf8Text(bytes);
        return text == null ? bytes : bytesOf(withoutTimestamps(text, volatileTimestamps));
    }

    /**
     * The file as text, or null where it is not UTF-8. Strictly: the default of a decoder is to replace what it
     * cannot read, which would make two different images hash alike.
     */
    private static String asUtf8Text(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("The generated file " + file + " could not be read to hash it.", e);
        }
    }

    private static byte[] bytesOf(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    private static IOException asIoException(Exception e) {
        return e instanceof UncheckedIOException unchecked ? unchecked.getCause() : (IOException) e;
    }
}
