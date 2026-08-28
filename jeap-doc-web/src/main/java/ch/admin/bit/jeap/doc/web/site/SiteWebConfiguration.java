package ch.admin.bit.jeap.doc.web.site;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;

import java.util.Map;

/**
 * Puts the documentation site at the end of the handler chain.
 * <p>
 * At {@link Ordered#LOWEST_PRECEDENCE} it is matched after the annotated controllers <b>and</b> after Spring's
 * resource handlers - which is what an annotated {@code @GetMapping("/**")} could not be: those are matched at
 * order 0 and would answer for the Swagger UI's assets and anything else served from a resource location.
 */
@Configuration
public class SiteWebConfiguration {

    @Bean
    SimpleUrlHandlerMapping documentationSiteHandlerMapping(SiteRequestHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(Ordered.LOWEST_PRECEDENCE);
        mapping.setUrlMap(Map.of("/**", handler));
        return mapping;
    }
}
