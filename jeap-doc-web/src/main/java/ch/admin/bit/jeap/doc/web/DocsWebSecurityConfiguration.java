package ch.admin.bit.jeap.doc.web;

import ch.admin.bit.jeap.doc.web.uiapi.UiApiPaths;
import ch.admin.bit.jeap.security.resource.token.AuthoritiesResolver;
import ch.admin.bit.jeap.security.resource.token.JeapAuthenticationConverter;
import ch.admin.bit.jeap.security.resource.validation.JeapJwtDecoderFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;

/**
 * The security of the doc service's REST API, and of the documentation it serves.
 * <p>
 * The two are opposites on purpose: the API is authenticated and authorized per system, and the documentation is
 * open to everyone. The actuator and the Swagger UI stay with the chains of the jEAP security, monitoring and
 * Swagger starters.
 */
@Configuration
public class DocsWebSecurityConfiguration {

    static final String API_PATH_PREFIX = "/api";

    /**
     * What the pages of the sites read in the reader's browser - {@link UiApiPaths}: open like the sites, and
     * read-only.
     * <p>
     * A chain of its own rather than an exception in the API chain, so that {@code /api/**} stays "a bearer token,
     * always". And not left to the site chain below, which would permit it only because it is "everything that is
     * not {@code /api}": that chain permits every method, and nothing below this root may be written to.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 25)
    SecurityFilterChain uiApiSecurityFilterChain(HttpSecurity http) {
        return http
                .securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(UiApiPaths.UI_API + "/**"))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, UiApiPaths.UI_API + "/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, UiApiPaths.UI_API + "/**").permitAll()
                        .anyRequest().denyAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    /**
     * The generated documentation, open to anyone who can reach the service.
     * <p>
     * Architecture documentation that everyone in the organisation should be able to read is of no use behind a
     * token a browser does not have, and nothing in this enabler asks for one: the alternative would be a login
     * flow, which is a story of its own. Reading is not authorization-free by accident - it is the decision.
     * <p>
     * Ordered after the API chain, so that {@code /api/**} keeps its bearer token, and before the chains of the
     * starters, which would otherwise answer for these paths.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 15)
    SecurityFilterChain documentationSiteSecurityFilterChain(HttpSecurity http) {
        return http
                // A path match, not a substring one: a generated page may perfectly well live at
                // /systems/orders/api/, and testing whether the URI *contains* "/api/" would drop exactly that
                // page out of this chain and answer a public documentation page with 401.
                .securityMatcher(new NegatedRequestMatcher(
                        PathPatternRequestMatcher.withDefaults().matcher(API_PATH_PREFIX + "/**")))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

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
                                                  AuthoritiesResolver authoritiesResolver) {
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
