package ch.admin.bit.jeap.doc.domain.architecture;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentedSystemTest {

    @Test
    void otherNames_leavesOutAnAliasThatOnlyRepeatsTheName() {
        assertThat(system("Orders", "orders", "ORDERS").otherNames()).isEmpty();
    }

    @Test
    void otherNames_namesEachAliasOnceInItsFirstSpelling() {
        assertThat(system("orders", "Order-Desk", "order-desk", "desk").otherNames())
                .containsExactly("Order-Desk", "desk");
    }

    @Test
    void otherNames_skipsBlankAliases() {
        assertThat(system("orders", " ", "desk").otherNames()).containsExactly("desk");
    }

    @Test
    void aliases_stillHoldEverySpellingForLookups() {
        assertThat(system("orders", "ORDERS").aliases()).containsExactly("ORDERS");
    }

    private static DocumentedSystem system(String name, String... aliases) {
        return new DocumentedSystem(name, name.toLowerCase(), null, Arrays.asList(aliases), null, List.of(),
                List.of(), List.of());
    }
}
