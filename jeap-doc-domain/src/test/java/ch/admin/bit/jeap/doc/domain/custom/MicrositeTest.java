package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What a generated page needs to know about one uploaded microsite. */
class MicrositeTest {

    private static final CustomProvenance PROVENANCE =
            new CustomProvenance("orders-docs", "main", "cafebabe", Instant.EPOCH, null, Instant.EPOCH);

    @Test
    void of_whenTheSetNamesNoPlacement_thenTheMicrositeHasNone() {
        Microsite microsite = Microsite.of(setOf(OrderHint.NONE));

        assertThat(microsite.hint()).isEqualTo(OrderHint.NONE);
    }

    /**
     * <b>The set carries the placement, not the page.</b> A microsite has no front matter of its own - its
     * three upload parameters are read onto the set the same way {@link UploadedTitles} reads a page's front
     * matter, and {@link Microsite#of} is where that hint reaches the page framing it.
     */
    @Test
    void of_whenTheSetNamesAPlacement_thenTheMicrositeCarriesIt() {
        OrderHint hint = new OrderHint(null, OrderHint.Direction.BEFORE, "system-context-view");

        Microsite microsite = Microsite.of(setOf(hint));

        assertThat(microsite.hint()).isEqualTo(hint);
    }

    /** A set stored before the hint existed carries none, read back the same way a microsite without one is. */
    @Test
    void of_whenTheSetWasStoredBeforeTheHintExisted_thenTheMicrositeHasNone() {
        CustomSet set = new CustomSet(1L, keyOf(), "Reference", 1L, "current/docs/1/1/files/", "abc", 10,
                PROVENANCE, List.of());

        assertThat(Microsite.of(set).hint()).isEqualTo(OrderHint.NONE);
    }

    private static CustomSet setOf(OrderHint hint) {
        return new CustomSet(1L, keyOf(), "Reference", 1L, "current/docs/1/1/files/", "abc", 10,
                PROVENANCE, List.of(), hint);
    }

    private static CustomSetKey keyOf() {
        return new CustomSetKey("default", SubjectKind.SYSTEM, "orders", null, SourceFormat.HTML, "arc42",
                "6-runtime-view", "reference");
    }
}
