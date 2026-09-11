package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.port.BundleLimits;
import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

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

    /** What a bundle may hold, for whoever reads one. The path count is the validation's own bound. */
    public BundleLimits limitsWith(UploadProperties uploadProperties) {
        return new BundleLimits(uploadProperties.getValidation().getMaxPaths(), maxUnpackedSize.toBytes());
    }

    @PostConstruct
    void check() {
        if (maxUnpackedSize.toBytes() < 1) {
            throw new IllegalStateException("jeap.doc.custom.max-unpacked-size is " + maxUnpackedSize
                                            + ". A documentation set holds at least one file.");
        }
    }
}
