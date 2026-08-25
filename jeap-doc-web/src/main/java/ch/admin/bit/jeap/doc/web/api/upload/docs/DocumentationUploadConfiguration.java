package ch.admin.bit.jeap.doc.web.api.upload.docs;

import ch.admin.bit.jeap.doc.domain.InvalidUploadException;
import ch.admin.bit.jeap.doc.web.api.upload.UploadParameterInterceptor;
import ch.admin.bit.jeap.doc.web.api.upload.UploadPaths;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Wires the endpoint receiving documentation: the check that runs before its parameters are bound.
 * <p>
 * The known parameters are the ones of a documentation upload, and the interceptor is registered on the path of
 * that kind of upload only - another kind brings its own parameters and registers its own.
 */
@Configuration
class DocumentationUploadConfiguration implements WebMvcConfigurer {

    static final List<String> KNOWN_QUERY_PARAMETERS = List.of(
            "site", "type", "system", "component", "library", "template", "source-format", "location", "topic",
            "label", "source-repository", "source-revision", "source-ref", "source-timestamp", "version",
            "build-url", "generated-at");

    @Bean
    UploadParameterInterceptor documentationUploadParameterInterceptor() {
        return new UploadParameterInterceptor(KNOWN_QUERY_PARAMETERS, InvalidUploadException::unknown);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(documentationUploadParameterInterceptor())
                .addPathPatterns(UploadPaths.DOCS + "/**");
    }
}
