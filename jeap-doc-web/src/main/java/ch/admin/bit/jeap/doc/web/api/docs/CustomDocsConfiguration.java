package ch.admin.bit.jeap.doc.web.api.docs;

import ch.admin.bit.jeap.doc.domain.upload.InvalidUploadException;
import ch.admin.bit.jeap.doc.web.api.upload.UploadParameterInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Wires the endpoints below {@code /api/docs/custom}: the check that runs before their parameters are
 * bound.
 * <p>
 * <b>The same reason an upload has one, and it is sharper here.</b> An unknown parameter on an upload publishes
 * something other than what the repository intended; an unknown parameter on a removal <i>deletes</i> something
 * other than what was meant - {@code sitte=governance} would fall through to the default site and take that
 * site's set away.
 * <p>
 * <b>A list per endpoint, because they take different parameters.</b> Removing a set names the one set: its
 * template, its format and, for a microsite, its section and slug. Removing a subject removes every set there
 * is, whatever their format and template - so passing one is not a narrower call but a misunderstanding, and
 * being told so is the point. Removing a system takes neither, and not even a type: it is everything
 * documented for that system.
 */
@Configuration
class CustomDocsConfiguration implements WebMvcConfigurer {

    /**
     * The parameters the three lists are drawn from, named once. They are the keys of the doc workflow
     * configuration, spelled exactly as an upload spells them - see {@code docs/api.md}.
     */
    private static final String SITE = "site";
    private static final String TYPE = "type";
    private static final String SYSTEM = "system";
    private static final String COMPONENT = "component";
    private static final String LIBRARY = "library";

    static final List<String> KNOWN_SET_QUERY_PARAMETERS = List.of(
            SITE, TYPE, SYSTEM, COMPONENT, LIBRARY, "template", "source-format", "location", "topic");

    static final List<String> KNOWN_SUBJECT_QUERY_PARAMETERS = List.of(
            SITE, TYPE, SYSTEM, COMPONENT, LIBRARY);

    /** Everything of one system: no type, because there is nothing to narrow - it is all of it. */
    static final List<String> KNOWN_SYSTEM_QUERY_PARAMETERS = List.of(SITE, SYSTEM);

    @Bean
    UploadParameterInterceptor customDocsSetParameterInterceptor() {
        return new UploadParameterInterceptor(KNOWN_SET_QUERY_PARAMETERS, InvalidUploadException::unknown);
    }

    @Bean
    UploadParameterInterceptor customDocsSubjectParameterInterceptor() {
        return new UploadParameterInterceptor(KNOWN_SUBJECT_QUERY_PARAMETERS, InvalidUploadException::unknown);
    }

    @Bean
    UploadParameterInterceptor customDocsSystemParameterInterceptor() {
        return new UploadParameterInterceptor(KNOWN_SYSTEM_QUERY_PARAMETERS, InvalidUploadException::unknown);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(customDocsSetParameterInterceptor())
                .addPathPatterns(DocsPaths.SETS);
        registry.addInterceptor(customDocsSubjectParameterInterceptor())
                .addPathPatterns(DocsPaths.SUBJECTS);
        registry.addInterceptor(customDocsSystemParameterInterceptor())
                .addPathPatterns(DocsPaths.SYSTEMS);
    }
}
