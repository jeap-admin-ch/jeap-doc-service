package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.SourceFormat;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The values the {@code source-format} parameter of an upload accepts, and the domain format each of them names.
 */
enum SourceFormatDto {

    MARKDOWN("markdown", SourceFormat.MARKDOWN),
    HTML("html", SourceFormat.HTML);

    private final String parameterValue;
    private final SourceFormat sourceFormat;

    SourceFormatDto(String parameterValue, SourceFormat sourceFormat) {
        this.parameterValue = parameterValue;
        this.sourceFormat = sourceFormat;
    }

    String parameterValue() {
        return parameterValue;
    }

    SourceFormat toDomain() {
        return sourceFormat;
    }

    static SourceFormatDto fromParameterValue(String value) {
        return Arrays.stream(values())
                .filter(format -> format.parameterValue.equals(value))
                .findFirst()
                .orElseThrow(() -> InvalidUploadException.invalidValue("source-format", value, acceptedValues()));
    }

    static String acceptedValues() {
        return Arrays.stream(values()).map(SourceFormatDto::parameterValue).collect(Collectors.joining(", "));
    }
}
