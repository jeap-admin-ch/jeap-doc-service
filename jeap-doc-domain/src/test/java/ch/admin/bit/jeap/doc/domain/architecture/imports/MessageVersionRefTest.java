package ch.admin.bit.jeap.doc.domain.architecture.imports;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When an index entry may stand in for a request: only on a tag both sides have and that is the same.
 */
class MessageVersionRefTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-11-06T08:00:00Z");

    @Test
    void hasSameContentAs_whenTheListedTagIsTheStoredOne_thenTrue() {
        assertThat(listed("\"sha256:a\"").hasSameContentAs(stored("\"sha256:a\""))).isTrue();
    }

    @Test
    void hasSameContentAs_whenTheListedTagMoved_thenFalse() {
        assertThat(listed("\"sha256:b\"").hasSameContentAs(stored("\"sha256:a\""))).isFalse();
    }

    @Test
    void hasSameContentAs_whenTheIndexListsNoTag_thenFalse() {
        assertThat(listed(null).hasSameContentAs(stored("\"sha256:a\""))).isFalse();
    }

    @Test
    void hasSameContentAs_whenNothingIsStored_thenFalse() {
        assertThat(listed("\"sha256:a\"").hasSameContentAs(null)).isFalse();
    }

    /** A row stored without a tag is fetched in full on every run; a blank listed tag must not confirm it. */
    @Test
    void hasSameContentAs_whenBothTagsAreBlank_thenFalse() {
        assertThat(listed("").hasSameContentAs(stored(""))).isFalse();
    }

    @Test
    void listed_withoutATag_thenTheEntryCarriesNone() {
        MessageVersionRef ref = MessageVersionRef.listed("dev", "orders", "OrdersPaidEvent", "1.0.0", "/url");

        assertThat(ref.etag()).isNull();
        assertThat(ref.contentUrl()).isEqualTo("/url");
        assertThat(ref.checkedAt()).isNull();
    }

    private static MessageVersionRef listed(String etag) {
        return MessageVersionRef.listed("dev", "orders", "OrdersPaidEvent", "1.0.0", "/url", etag);
    }

    private static MessageVersionRef stored(String etag) {
        return MessageVersionRef.stored("dev", "orders", "OrdersPaidEvent", "1.0.0", etag, CHECKED_AT);
    }
}
