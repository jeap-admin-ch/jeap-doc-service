package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.DocumentationType;
import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The values the {@code type} parameter accepts, and the domain type each of them names.
 * <p>
 * Public because every endpoint that names a placement reads it - the upload, the structure validation and
 * the removal - and one mapping of the parameter vocabulary is what keeps their answers the same.
 */
public enum DocumentationTypeDto {

    SYSTEM_DOCS("system-docs", DocumentationType.SYSTEM_DOCS),
    COMPONENT_DOCS("component-docs", DocumentationType.COMPONENT_DOCS),
    LIBRARY_DOCS("library-docs", DocumentationType.LIBRARY_DOCS);

    private final String parameterValue;
    private final DocumentationType type;

    DocumentationTypeDto(String parameterValue, DocumentationType type) {
        this.parameterValue = parameterValue;
        this.type = type;
    }

    String parameterValue() {
        return parameterValue;
    }

    public DocumentationType toDomain() {
        return type;
    }

    static DocumentationTypeDto of(DocumentationType type) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.type == type)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No parameter value names %s.".formatted(type)));
    }

    public static DocumentationTypeDto fromParameterValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.parameterValue.equals(value))
                .findFirst()
                .orElseThrow(() -> InvalidUploadException.invalidValue("type", value, acceptedValues()));
    }

    static String acceptedValues() {
        return Arrays.stream(values()).map(DocumentationTypeDto::parameterValue).collect(Collectors.joining(", "));
    }
}
