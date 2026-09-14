package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.port.UploadedBundles;
import ch.admin.bit.jeap.doc.domain.port.BundleLimits;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;

import java.time.Instant;
import java.util.List;

/**
 * A storage nothing has been uploaded into, for the tests that are about the generated pages.
 */
public class NoCustomStorage implements CustomDocumentationStorage {
    @Override
    public String promoteFiles(UploadedBundles.ReceivedBundle received, CustomSetKey key, long revision,
                               int attempt, BundleLimits limits) {
        throw new UnsupportedOperationException("Nothing is uploaded in this test.");
    }

    @Override
    public java.util.Optional<ch.admin.bit.jeap.doc.domain.port.StoredObject> openFile(String prefix, String path) {
        return java.util.Optional.empty();
    }


    public static final NoCustomStorage INSTANCE = new NoCustomStorage();

    @Override
    public String promote(StoredBundle stored, CustomSetKey key, long revision, int attempt) {
        throw new UnsupportedOperationException("nothing is uploaded in this test");
    }

    @Override
    public java.util.Optional<OpenedBundle> open(CustomSet set) {
        throw new UnsupportedOperationException("no set is read in this test");
    }

    @Override
    public void delete(String objectKey) {
        throw new UnsupportedOperationException("nothing is removed in this test");
    }

    @Override
    public List<String> listWrittenBefore(Instant writtenBefore) {
        return List.of();
    }

    @Override
    public void storeSearchText(String prefix, java.util.List<
            ch.admin.bit.jeap.doc.domain.custom.MicrositePageText> pages) {
        // Nothing is indexed here.
    }

    @Override
    public java.util.List<ch.admin.bit.jeap.doc.domain.custom.MicrositePageText> readSearchText(String prefix) {
        return java.util.List.of();
    }
}
