package ch.admin.bit.jeap.doc.upstream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * What an upstream's configuration has to say before this service starts.
 * <p>
 * <b>Configuration errors fail the startup, not the first request</b>, which is this service's rule for all of
 * them. Both upstream adapters configure the same two things - where the upstream is, and which client
 * registration the token comes from - so they check them the same way and neither writes its own version of
 * these messages.
 */
@Slf4j
public final class UpstreamChecks {

    private UpstreamChecks() {
    }

    /**
     * The URL of an upstream has to be absolute, because a URL out of one of its payloads is resolved against
     * its origin. Without a scheme and a host there is no origin to resolve against, and the first fetch of
     * the first import would be what finds out.
     *
     * @param property the property that carries it, named in full, so the message says what to edit
     */
    public static void requireAnAbsoluteUrl(String property, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("%s is not configured.".formatted(property));
        }
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("%s is '%s', which is not a URL.".formatted(property, url), e);
        }
        if (parsed.getScheme() == null || parsed.getHost() == null) {
            throw new IllegalStateException(("%s is '%s', which names no scheme and host. It has to be "
                                             + "absolute, because a URL out of the upstream's own payloads is "
                                             + "resolved against its origin.").formatted(property, url));
        }
    }

    /**
     * The token comes from a client registration of this instance, and the client behind it needs a role on
     * <b>that upstream's</b> authorization server rather than this service's own.
     * <p>
     * A missing registry is a warning and not a failure: it is what a test looks like, and there is nothing to
     * check the name against. A registry that does not know the name is a failure, because every import of
     * that environment would fail an hour from now.
     *
     * @param property      the property that carries it, named in full
     * @param role          the semantic role the client needs, for the message
     * @param registrations the registry of this instance, or null when it has none
     */
    public static void requireAClientRegistration(String property, String registration, String role,
                                                  ClientRegistrationRepository registrations) {
        if (registration == null || registration.isBlank()) {
            throw new IllegalStateException(("%s is not configured. This service reads the upstream with a "
                                             + "client-credentials token, and the client needs the role %s.")
                    .formatted(property, role));
        }
        if (registrations == null) {
            log.warn("{} names the client registration '{}', and this instance has no OAuth2 client registry "
                     + "to resolve it against. Unless the token comes from elsewhere, configure it under "
                     + "spring.security.oauth2.client.registration.", property, registration);
            return;
        }
        if (registrations.findByRegistrationId(registration) == null) {
            throw new IllegalStateException(("%s is '%s', and no such client registration is configured under "
                                             + "spring.security.oauth2.client.registration. Without it every "
                                             + "import of this environment would fail an hour from now.")
                    .formatted(property, registration));
        }
    }
}
