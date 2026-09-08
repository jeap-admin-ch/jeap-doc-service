package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.domain.upload.UploadProperties;
import lombok.RequiredArgsConstructor;
import ch.admin.bit.jeap.doc.web.api.upload.UploadParameterInterceptor;
import ch.admin.bit.jeap.doc.web.api.upload.UploadPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Wires the two endpoints below {@code /api/uploads/docs}: the check that runs before their parameters are
 * bound.
 * <p>
 * <b>Two interceptors and one list each</b>, rather than one list that is right for neither. An upload names
 * where its documents came from; a structure validation answers a question about a path tree, which does not
 * depend on a commit hash - so a request carrying one is a typo, and being told so is the point of the
 * interceptor. The validation path is therefore excluded from the upload's patterns and gets its own.
 */
@Configuration
@RequiredArgsConstructor
class DocumentationUploadConfiguration implements WebMvcConfigurer {

    private final UploadProperties uploadProperties;

    static final List<String> KNOWN_QUERY_PARAMETERS = List.of(
            "site", "type", "system", "component", "library", "template", "source-format", "location", "topic",
            "label", "source-repository", "source-revision", "source-ref", "source-timestamp", "version",
            "build-url", "generated-at");

    /**
     * What the structure depends on, and nothing else. No {@code site}: a tree is validated against a
     * template, and which site publishes it changes none of its rules.
     */
    static final List<String> KNOWN_VALIDATION_QUERY_PARAMETERS = List.of(
            "type", "system", "component", "library", "template", "source-format", "location", "topic");

    static final String VALIDATION_PATH = UploadPaths.DOCS + DocumentationValidationController.VALIDATION_PATH;

    @Bean
    UploadParameterInterceptor documentationUploadParameterInterceptor() {
        return new UploadParameterInterceptor(KNOWN_QUERY_PARAMETERS, InvalidUploadException::unknown);
    }

    @Bean
    UploadParameterInterceptor documentationValidationParameterInterceptor() {
        return new UploadParameterInterceptor(KNOWN_VALIDATION_QUERY_PARAMETERS,
                InvalidUploadException::unknown);
    }

    /** Refuses an oversized body before it is read - see {@link ValidationBodySizeInterceptor}. */
    @Bean
    ValidationBodySizeInterceptor validationBodySizeInterceptor(UploadProperties uploadProperties) {
        return new ValidationBodySizeInterceptor(uploadProperties);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(documentationUploadParameterInterceptor())
                .addPathPatterns(UploadPaths.DOCS + "/**")
                .excludePathPatterns(VALIDATION_PATH);
        registry.addInterceptor(documentationValidationParameterInterceptor())
                .addPathPatterns(VALIDATION_PATH);
        registry.addInterceptor(validationBodySizeInterceptor(uploadProperties))
                .addPathPatterns(VALIDATION_PATH);
    }
}
