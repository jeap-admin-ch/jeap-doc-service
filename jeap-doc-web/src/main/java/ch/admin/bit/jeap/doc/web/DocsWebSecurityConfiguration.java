package ch.admin.bit.jeap.doc.web;

import ch.admin.bit.jeap.security.resource.token.AuthoritiesResolver;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationConverter;
import ch.admin.bit.jeap.security.resource.validation.JeapJwtDecoderFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The security of the doc service's REST API.
 * <p>
 * Everything else - the actuator, the Swagger UI - stays with the chains of the jEAP security and Swagger
 * starters.
 */
@Configuration
public class DocsWebSecurityConfiguration {

    static final String API_PATH_PREFIX = "/api";

    /**
     * The REST API, authenticated with a bearer token exactly like the chain of the jEAP security starter, but
     * without CSRF protection: the API is called by build pipelines that hold a token and no cookie, so the CSRF
     * token of a browser session neither exists nor adds anything, while its absence would reject every upload.
     * <p>
     * Registered with a higher precedence than the chains of the jEAP security starter, which use orders relative
     * to {@link Ordered#LOWEST_PRECEDENCE}.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 20)
    SecurityFilterChain docsApiSecurityFilterChain(HttpSecurity http, JeapJwtDecoderFactory jeapJwtDecoderFactory,
                                                  AuthoritiesResolver authoritiesResolver) throws Exception {
        return http
                .securityMatcher(API_PATH_PREFIX + "/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().fullyAuthenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt
                        .decoder(jeapJwtDecoderFactory.createJwtDecoder())
                        .jwtAuthenticationConverter(new JeapAuthenticationConverter(authoritiesResolver))))
                .build();
    }
}
