package ch.admin.bit.jeap.doc.web.site.browser;

import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.port.CustomDocumentationStorage;
import ch.admin.bit.jeap.doc.domain.port.StoredBundle;

import java.time.Instant;
import java.util.List;

/**
 * A storage nothing has been uploaded into. This suite writes the pages it needs by hand.
 */
public class NoCustomStorage implements CustomDocumentationStorage {

    @Override
    public String promote(StoredBundle stored, CustomSetKey key, long revision, int attempt) {
        throw new UnsupportedOperationException("nothing is uploaded in this suite");
    }

    @Override
    public java.util.Optional<OpenedBundle> open(CustomSet set) {
        throw new UnsupportedOperationException("no set is read in this suite");
    }

    @Override
    public void delete(String objectKey) {
        throw new UnsupportedOperationException("nothing is removed in this test");
    }

    @Override
    public List<String> listWrittenBefore(Instant writtenBefore) {
        return List.of();
    }
}
