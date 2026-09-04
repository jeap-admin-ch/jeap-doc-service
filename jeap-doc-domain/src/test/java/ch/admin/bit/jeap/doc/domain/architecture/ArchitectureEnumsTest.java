package ch.admin.bit.jeap.doc.domain.architecture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the values the architecture repository sends are read.
 * <p>
 * They arrive as strings, and the landscape is described by importers that gain new kinds without asking. A
 * value this service has not heard of must never fail a build - it becomes the unknown constant, and the page
 * says so.
 */
class ArchitectureEnumsTest {

    @ParameterizedTest
    @CsvSource({
            "REST_API_RELATION,REST_API",
            "EVENT_RELATION,EVENT",
            "COMMAND_RELATION,COMMAND",
            "rest_api_relation,REST_API"})
    void relationKind_readsWhatTheRepositorySends(String sent, RelationKind expected) {
        assertThat(RelationKind.of(sent)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "SOMETHING_NEW_RELATION", "EVENT"})
    void relationKind_whenTheKindIsUnknown_thenItIsOther(String sent) {
        assertThat(RelationKind.of(sent)).isEqualTo(RelationKind.OTHER);
    }

    @Test
    void relationKind_everyKindHasAVerbAPageCanUse() {
        for (RelationKind kind : RelationKind.values()) {
            assertThat(kind.verb()).isNotBlank();
        }
    }

    /**
     * What an arrow says instead of the names when there are more of them than it can carry. It is the whole
     * phrase, because {@code REST Call} does not pluralise by adding an <i>s</i>.
     */
    @ParameterizedTest
    @CsvSource({
            "EVENT,5,5 Events",
            "EVENT,1,1 Event",
            "COMMAND,6,6 Commands",
            "COMMAND,1,1 Command",
            "REST_API,3,3 REST Calls",
            "REST_API,1,1 REST Call",
            "OTHER,2,2 Relations",
            "OTHER,1,1 Relation"})
    void relationKind_countsWhatTravelsAlongOneArrow(RelationKind kind, int howMany, String expected) {
        assertThat(kind.count(howMany)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "BACKEND_SERVICE,BACKEND_SERVICE",
            "backend_service,BACKEND_SERVICE",
            "Frontend,FRONTEND",
            "GATEWAY,GATEWAY",
            "SELF_CONTAINED_SYSTEM,SELF_CONTAINED_SYSTEM"})
    void componentType_readsWhatTheRepositorySends(String sent, ComponentType expected) {
        assertThat(ComponentType.of(sent)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "QUANTUM_SERVICE"})
    void componentType_whenTheTypeIsUnknown_thenItIsUnknown(String sent) {
        assertThat(ComponentType.of(sent)).isEqualTo(ComponentType.UNKNOWN);
    }

    @Test
    void componentType_everyTypeHasALabelAPageCanShow() {
        for (ComponentType type : ComponentType.values()) {
            assertThat(type.label()).isNotBlank();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "PUBLISHER,PRODUCES",
            "SENDER,PRODUCES",
            "CONSUMER,CONSUMES",
            "RECEIVER,CONSUMES",
            "publisher,PRODUCES"})
    void contractRole_readsWhichSideOfAMessageAComponentIsOn(String sent, ContractRole expected) {
        assertThat(ContractRole.of(sent)).isEqualTo(expected);
    }

    /**
     * Which side a component is on is the one thing a message page is read for, so an unknown role must not
     * quietly become one of the two real ones.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "OBSERVER", "PRODUCES"})
    void contractRole_whenTheRoleIsUnknown_thenItIsNeitherSide(String sent) {
        assertThat(ContractRole.of(sent)).isEqualTo(ContractRole.UNKNOWN);
    }

    @ParameterizedTest
    @CsvSource({"COMMAND,COMMAND", "command,COMMAND", "EVENT,EVENT"})
    void messageKind_readsWhatTheRepositorySends(String sent, MessageKind expected) {
        assertThat(MessageKind.of(sent)).isEqualTo(expected);
    }

    /**
     * A message is an event unless it says it is a command. There are only these two, and a page has to name
     * one of them.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "QUERY"})
    void messageKind_whenTheKindIsUnknown_thenItIsAnEvent(String sent) {
        assertThat(MessageKind.of(sent)).isEqualTo(MessageKind.EVENT);
    }

    @Test
    void messageKind_namesBothSidesOfEachKind() {
        assertThat(MessageKind.EVENT.producerRole()).isEqualTo("Publisher");
        assertThat(MessageKind.EVENT.consumerRole()).isEqualTo("Consumer");
        assertThat(MessageKind.COMMAND.producerRole()).isEqualTo("Sender");
        assertThat(MessageKind.COMMAND.consumerRole()).isEqualTo("Receiver");
        for (MessageKind kind : MessageKind.values()) {
            assertThat(kind.label()).isNotBlank();
            assertThat(kind.plural()).isNotBlank();
        }
    }
}
