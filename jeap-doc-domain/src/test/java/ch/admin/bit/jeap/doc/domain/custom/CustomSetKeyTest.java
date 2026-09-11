package ch.admin.bit.jeap.doc.domain.custom;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationPlacement;
import ch.admin.bit.jeap.doc.domain.upload.DocumentationType;
import ch.admin.bit.jeap.doc.domain.upload.SourceFormat;
import ch.admin.bit.jeap.doc.domain.upload.SubjectKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What identifies a set, and therefore what a further upload replaces. */
class CustomSetKeyTest {

    private static DocumentationPlacement markdownFor(DocumentationType type, String component, String library) {
        return new DocumentationPlacement(type, "orders", component, library, "arc42", SourceFormat.MARKDOWN,
                null, null);
    }

    @Test
    void ofASystem_isKeyedByTheSystemAndNothingElse() {
        CustomSetKey key = CustomSetKey.of("default",
                markdownFor(DocumentationType.SYSTEM_DOCS, null, null));

        assertThat(key.kind()).isEqualTo(SubjectKind.SYSTEM);
        assertThat(key.system()).isEqualTo("orders");
        assertThat(key.name()).isNull();
        assertThat(key.subject()).isEqualTo(new CustomSubject("default", SubjectKind.SYSTEM, "orders", null));
    }

    @Test
    void ofAComponentAndOfALibrary_carryTheirName() {
        CustomSetKey component = CustomSetKey.of("default",
                markdownFor(DocumentationType.COMPONENT_DOCS, "orders-intake", null));
        CustomSetKey library = CustomSetKey.of("default",
                markdownFor(DocumentationType.LIBRARY_DOCS, null, "orders-client"));

        assertThat(component.name()).isEqualTo("orders-intake");
        assertThat(library.name()).isEqualTo("orders-client");
        assertThat(component).isNotEqualTo(library);
    }

    @Test
    void markdown_carriesNoLocationAndNoTopic() {
        CustomSetKey key = CustomSetKey.of("default",
                markdownFor(DocumentationType.SYSTEM_DOCS, null, null));

        assertThat(key.location()).isNull();
        assertThat(key.topic()).isNull();
    }

    @Test
    void html_isItsOwnSetPerLocationAndTopic() {
        CustomSetKey reference = CustomSetKey.of("default", new DocumentationPlacement(
                DocumentationType.COMPONENT_DOCS, "orders", "orders-intake", null, "arc42",
                SourceFormat.HTML, "6-runtime-view", "configuration-reference"));
        CustomSetKey other = CustomSetKey.of("default", new DocumentationPlacement(
                DocumentationType.COMPONENT_DOCS, "orders", "orders-intake", null, "arc42",
                SourceFormat.HTML, "6-runtime-view", "api-reference"));

        assertThat(reference).isNotEqualTo(other);
        assertThat(reference.subject()).describedAs("two microsites of one component")
                .isEqualTo(other.subject());
    }

    @Test
    void theTemplateIsPartOfTheKey_soASwitchDoesNotReplace() {
        CustomSetKey arc42 = CustomSetKey.of("default",
                markdownFor(DocumentationType.SYSTEM_DOCS, null, null));
        CustomSetKey other = new CustomSetKey("default", SubjectKind.SYSTEM, "orders", null,
                SourceFormat.MARKDOWN, "something-else", null, null);

        assertThat(arc42).isNotEqualTo(other);
    }

    @Test
    void markdownAndHtmlOfOneSubject_areDifferentSets() {
        CustomSetKey markdown = CustomSetKey.of("default",
                markdownFor(DocumentationType.SYSTEM_DOCS, null, null));
        CustomSetKey html = new CustomSetKey("default", SubjectKind.SYSTEM, "orders", null,
                SourceFormat.HTML, "arc42", "6-runtime-view", "configuration-reference");

        assertThat(markdown).isNotEqualTo(html);
    }

    @Test
    void aSubject_isASystemWithoutANameOrSomethingElseWithOne() {
        assertThatThrownBy(() -> new CustomSubject("default", SubjectKind.SYSTEM, "orders", "orders-intake"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CustomSubject("default", SubjectKind.COMPONENT, "orders", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new CustomSubject("default", SubjectKind.SYSTEM, "orders", null).slug())
                .isEqualTo("orders");
        assertThat(new CustomSubject("default", SubjectKind.LIBRARY, "orders", "orders-client").slug())
                .isEqualTo("orders-client");
    }
}
