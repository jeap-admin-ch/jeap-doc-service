package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.port.BundleLimits;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import ch.admin.bit.jeap.doc.domain.upload.validation.MarkdownAssetRules;
import ch.admin.bit.jeap.doc.domain.upload.validation.MicrositeRules;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the doc service accepts as a documentation set.
 */
@Data
@ConfigurationProperties(prefix = "jeap.doc.custom")
public class CustomProperties {

    /**
     * The most a set may unpack to, added up over its files.
     * <p>
     * A build writes every file of a set into the tree it generates, so a bundle that unpacks to far more than
     * it weighs would fill the disk of a build task long after the upload was accepted. Checked twice: against
     * what the archive declares when the set is received, and against the bytes actually written when a build
     * writes them - the declared sizes are the uploader's to state, so only the second one measures.
     */
    private DataSize maxUnpackedSize = DataSize.ofMegabytes(200);

    /**
     * When the objects no documentation set names are swept up.
     * <p>
     * Nightly, after the other clean-ups. A dash switches it off. Note what this is <b>not</b>: nothing under
     * the current documentation is removed for being old - see {@code CustomDocumentationSweep}.
     */
    private String sweepCron = "0 50 2 * * *";

    /**
     * What an HTML microsite may <b>not</b> carry.
     * <p>
     * A microsite follows no template, so there is no allowlist to bound it: a build emits file types
     * nobody listed in advance. This refuses what has no business in documentation - see
     * {@link MicrositeRules#REFUSED_BY_DEFAULT}.
     */
    private Set<String> refusedExtensions = MicrositeRules.REFUSED_BY_DEFAULT;

    /**
     * Asset types a Markdown set may carry on top of its template's list. It only adds, and it cannot add
     * anything on {@link MarkdownAssetRules#isNeverAllowed}. An HTML microsite ignores it.
     */
    private Set<String> additionalAssetExtensions = Set.of();

    /**
     * What a bundle may hold, for whoever reads one. The path count is the validation's own bound, and it
     * differs per source format: a microsite is a built site, a markdown set a chapter of pages.
     */
    public BundleLimits limitsWith(UploadProperties uploadProperties, SourceFormat sourceFormat) {
        return new BundleLimits(uploadProperties.getValidation().maxPathsOf(sourceFormat),
                maxUnpackedSize.toBytes());
    }

    @PostConstruct
    void check() {
        // Compared against an extension that was lower-cased, so a list written in capitals still applies.
        refusedExtensions = refusedExtensions.stream()
                .map(extension -> extension.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        additionalAssetExtensions = normalized(additionalAssetExtensions);
        for (String extension : additionalAssetExtensions) {
            if (MarkdownAssetRules.isNeverAllowed(extension)) {
                throw new IllegalStateException(("jeap.doc.custom.additional-asset-extensions names '%s', which "
                        + "can never be an asset: it is a page type, a document or code the site would render or "
                        + "run, or an executable.").formatted(extension));
            }
        }
        if (maxUnpackedSize.toBytes() < 1) {
            throw new IllegalStateException("jeap.doc.custom.max-unpacked-size is " + maxUnpackedSize
                                            + ". A documentation set holds at least one file.");
        }
    }

    /** Lower-cased, without a leading dot, and without blank entries. */
    private static Set<String> normalized(Set<String> extensions) {
        if (extensions == null) {
            return Set.of();
        }
        return extensions.stream()
                .filter(extension -> extension != null && !extension.isBlank())
                .map(extension -> extension.strip().toLowerCase(Locale.ROOT))
                .map(extension -> extension.startsWith(".") ? extension.substring(1) : extension)
                .filter(extension -> !extension.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
