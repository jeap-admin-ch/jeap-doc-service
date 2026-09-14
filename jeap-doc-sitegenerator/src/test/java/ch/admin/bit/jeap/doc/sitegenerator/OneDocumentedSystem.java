package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.custom.CustomDocumentation;
import ch.admin.bit.jeap.doc.domain.custom.CustomPage;
import ch.admin.bit.jeap.doc.domain.custom.CustomProvenance;
import ch.admin.bit.jeap.doc.domain.custom.CustomSet;
import ch.admin.bit.jeap.doc.domain.custom.CustomSetKey;
import ch.admin.bit.jeap.doc.domain.custom.CustomSubject;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;

import java.time.Instant;
import java.util.List;

/**
 * Exactly one system documented, which no architecture model holds - a system documented before anything of
 * it is deployed.
 * <p>
 * Its pages are never read: what the tests using it assert is that such a system is written into the tree and
 * named by the navigation at all.
 */
public class OneDocumentedSystem extends NoCustomDocumentation {

    private final String slug;

    public OneDocumentedSystem(String slug) {
        this.slug = slug;
    }

    @Override
    public CustomDocumentation of(String site, String system) {
        return system.equals(slug)
                ? new CustomDocumentation(List.of(setOf(site)))
                : CustomDocumentation.nothing();
    }

    @Override
    public List<CustomSubject> subjectsOf(String site) {
        return List.of(new CustomSubject(site, SubjectKind.SYSTEM, slug, null));
    }

    private CustomSet setOf(String site) {
        return new CustomSet(1L,
                new CustomSetKey(site, SubjectKind.SYSTEM, slug, null, SourceFormat.MARKDOWN, "arc42", null,
                        null),
                null, 7L, "current/docs/1/bundle.zip", "abc", 10,
                new CustomProvenance(slug + "-docs", "main", "cafebabe", Instant.EPOCH, null, Instant.EPOCH),
                List.of(new CustomPage("1-intro", "goals.md", "Goals", 1, false)));
    }
}
