package ch.admin.bit.jeap.doc.web.api;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What an uploaded documentation set documents: a system as a whole, one of its components, or one of its
 * libraries.
 */
public enum DocumentationSetType {

    SYSTEM_DOCS("system-docs"),
    COMPONENT_DOCS("component-docs"),
    LIBRARY_DOCS("library-docs");

    private final String parameterValue;

    DocumentationSetType(String parameterValue) {
        this.parameterValue = parameterValue;
    }

    public String parameterValue() {
        return parameterValue;
    }

    public static DocumentationSetType fromParameterValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.parameterValue.equals(value))
                .findFirst()
                .orElseThrow(() -> InvalidUploadException.invalidValue("type", value, acceptedValues()));
    }

    public static String acceptedValues() {
        return Arrays.stream(values()).map(DocumentationSetType::parameterValue).collect(Collectors.joining(", "));
    }
}
