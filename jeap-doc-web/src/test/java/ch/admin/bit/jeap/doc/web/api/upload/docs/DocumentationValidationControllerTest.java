package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationType;
import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.upload.SiteUploadRules;
import ch.admin.bit.jeap.doc.domain.upload.validation.StructureReport;
import ch.admin.bit.jeap.doc.domain.upload.validation.StructureValidation;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which dry run asks for its site. The rules themselves are {@code SiteUploadRules}'; the instance every IT of this
 * module runs serves a default site, so that a system's dry run naming none is answered without one is shown here.
 */
class DocumentationValidationControllerTest {

    private final StructureValidation validation = mock(StructureValidation.class);
    private final PathTreeReader pathTreeReader = mock(PathTreeReader.class);
    private final SiteUploadRules siteUploadRules = mock(SiteUploadRules.class);
    private final DocumentationValidationController controller =
            new DocumentationValidationController(validation, pathTreeReader, siteUploadRules);

    /** A system's dry run named no site before sites took uploads, and an instance need not serve a default one. */
    @Test
    void validate_whenASystemsDocumentationNamesNoSite_thenNoSiteIsAskedFor() {
        when(pathTreeReader.read(any(MockHttpServletRequest.class), any())).thenReturn(List.of("1-intro/goals.md"));
        when(validation.validate(any(), any(), any())).thenReturn(
                new StructureReport("arc42", 1, 0, List.of(), List.of(), List.of(), List.of(), 0));

        var answer = controller.validate(null, "system-docs", "orders", null, null, "arc42", "markdown", null,
                null, new MockHttpServletRequest());

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(siteUploadRules);
    }

    @Test
    void validate_whenASiteIsNamed_thenItHasToTakeTheTypeBeforeTheTreeIsRead() {
        doThrow(InvalidUploadException.invalidValue("type", "SYSTEM_DOCS", "SITE_DOCS"))
                .when(siteUploadRules).requireTheSiteTakes("handbook", DocumentationType.SYSTEM_DOCS);

        assertThatThrownBy(() -> controller.validate("handbook", "system-docs", "orders", null, null, "arc42",
                "markdown", null, null, new MockHttpServletRequest()))
                .isInstanceOf(InvalidUploadException.class);
        verifyNoInteractions(pathTreeReader, validation);
    }
}
