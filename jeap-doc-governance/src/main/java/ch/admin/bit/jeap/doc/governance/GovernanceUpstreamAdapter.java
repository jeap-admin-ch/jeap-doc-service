package ch.admin.bit.jeap.doc.governance;

import ch.admin.bit.jeap.doc.domain.governance.GovernanceResource;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceKind;
import ch.admin.bit.jeap.doc.domain.governance.GovernanceResourceRef;
import ch.admin.bit.jeap.doc.domain.port.GovernanceIndexFetch;
import ch.admin.bit.jeap.doc.domain.port.GovernanceResourceFetch;
import ch.admin.bit.jeap.doc.domain.port.GovernanceUnavailableException;
import ch.admin.bit.jeap.doc.domain.port.GovernanceUpstream;
import ch.admin.bit.jeap.doc.upstream.UpstreamException;
import ch.admin.bit.jeap.doc.upstream.UpstreamHttp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Governance Service's {@code /docs-api} as the governance import reads it: the two indexes, and the
 * system or rule resource behind one entry of either.
 * <p>
 * Every request here is conditional. The indexes carry the time of the last completed scoring run, so they
 * change at every run; a system or rule resource moves far less often, and is fetched only where its own
 * entity tag did.
 */
@Slf4j
@RequiredArgsConstructor
class GovernanceUpstreamAdapter implements GovernanceUpstream {

    private static final String SYSTEMS_INDEX_PATH = "/docs-api/systems";
    private static final String RULES_INDEX_PATH = "/docs-api/rules";

    private final GovernanceClients clients;
    private final GovernanceProperties properties;
    private final Clock clock;

    @Override
    public boolean isConfigured() {
        return clients.isConfigured();
    }

    @Override
    public Optional<GovernanceIndexFetch> systemsIndex(String knownIndexEtag) {
        return index("the systems", SYSTEMS_INDEX_PATH, knownIndexEtag, body -> {
            GovernanceDtos.SystemIndexDto dto = clients.readJson(body, GovernanceDtos.SystemIndexDto.class);
            if (dto == null || dto.systems() == null) {
                throw new GovernanceUnavailableException(
                        "The Governance Service answered the systems index without a list of systems. The "
                        + "stored systems are kept: an empty index deletes every one of them, and an answer "
                        + "that lists nothing is not an index that is empty.");
            }
            requireIdentityAndEtag("systems", dto.systems().stream()
                    .allMatch(entry -> entry.name() != null && entry.etag() != null));
            return dto.systems().stream()
                    .map(entry -> new GovernanceResourceRef(GovernanceResourceKind.SYSTEM, entry.name(),
                            entry.etag()))
                    .toList();
        });
    }

    @Override
    public Optional<GovernanceIndexFetch> rulesIndex(String knownIndexEtag) {
        return index("the rules", RULES_INDEX_PATH, knownIndexEtag, body -> {
            GovernanceDtos.RuleIndexDto dto = clients.readJson(body, GovernanceDtos.RuleIndexDto.class);
            if (dto == null || dto.rules() == null) {
                throw new GovernanceUnavailableException(
                        "The Governance Service answered the rules index without a list of rules. The stored "
                        + "rules are kept: an empty index deletes every one of them, and an answer that lists "
                        + "nothing is not an index that is empty.");
            }
            requireIdentityAndEtag("rules", dto.rules().stream()
                    .allMatch(entry -> entry.ruleId() != null && entry.etag() != null));
            return dto.rules().stream()
                    .map(entry -> new GovernanceResourceRef(GovernanceResourceKind.RULE, entry.ruleId(),
                            entry.etag()))
                    .toList();
        });
    }

    /**
     * An entry that is left out of the list reads as withdrawn, and the import deletes what is stored for it.
     * So an index with one unusable entry is refused as a whole, before anything is changed.
     */
    private static void requireIdentityAndEtag(String subject, boolean allUsable) {
        if (!allUsable) {
            throw new GovernanceUnavailableException(("The Governance Service answered the %s index with an "
                    + "entry that has no identity or no entity tag. The stored %s are kept: an entry left out "
                    + "would be taken as withdrawn.").formatted(subject, subject));
        }
    }

    @Override
    public GovernanceResourceFetch system(String systemName, String knownEtag) {
        return content(GovernanceResourceKind.SYSTEM, "system", systemPath(systemName), systemName, knownEtag);
    }

    @Override
    public GovernanceResourceFetch rule(String ruleId, String knownEtag) {
        return content(GovernanceResourceKind.RULE, "rule", rulePath(ruleId), ruleId, knownEtag);
    }

    private Optional<GovernanceIndexFetch> index(String subject, String path, String knownEtag,
                                                 Function<byte[], List<GovernanceResourceRef>> parse) {
        requireConfigured();
        long cap = properties.getMaxResourceSize().toBytes();
        UpstreamHttp.Answer answer = call(() -> clients.retrying(() -> clients.getBounded(path, knownEtag, cap)));
        if (answer.isNotModified()) {
            return Optional.empty();
        }
        if (answer.isRedirect()) {
            throw new GovernanceUnavailableException(("The Governance Service answered %s index with a "
                    + "redirect to %s, which is not followed.").formatted(subject, answer.location()));
        }
        if (answer.tooLarge()) {
            throw new GovernanceUnavailableException(("%s index of the Governance Service is larger than the "
                    + "%s this service stores.").formatted(subject, properties.getMaxResourceSize()));
        }
        String etag = answer.etag();
        if (etag == null || etag.isBlank()) {
            throw new GovernanceUnavailableException(
                    "%s index of the Governance Service arrived without an entity tag.".formatted(subject));
        }
        byte[] body = answer.body() == null ? new byte[0] : answer.body();
        if (body.length == 0) {
            // Spring's typed retrieval turns a zero-length 200 into a null body, which a caller tells apart
            // from an empty list; a raw byte read like this one has no such distinction, and handing an empty
            // array to the JSON parser would throw instead of saying what is actually wrong.
            throw new GovernanceUnavailableException(("%s index of the Governance Service answered with no "
                    + "body. The stored resources are kept: an empty index deletes every one of them, and an "
                    + "answer that lists nothing is not an index that is empty.").formatted(subject));
        }
        try {
            return Optional.of(new GovernanceIndexFetch(body, etag, parse.apply(body)));
        } catch (GovernanceUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            // tools.jackson's exceptions are unchecked, and malformed JSON must read as the index being
            // unavailable, like every other way it could fail to answer.
            throw new GovernanceUnavailableException(
                    "%s index of the Governance Service could not be read as JSON: %s"
                            .formatted(subject, e.getMessage()), e);
        }
    }

    private GovernanceResourceFetch content(GovernanceResourceKind kind, String subject, String path, String key,
                                            String knownEtag) {
        requireConfigured();
        long cap = properties.getMaxResourceSize().toBytes();
        UpstreamHttp.Answer answer;
        try {
            answer = call(() -> clients.retrying(() -> clients.getBounded(path, knownEtag, cap)));
        } catch (GovernanceUnavailableException e) {
            if (e.getCause() instanceof UpstreamException upstream && upstream.isNotFound()) {
                // It went away between the index and the fetch, which is a race and not an error. The index
                // is asked unconditionally on the next run, so a resource that comes back is offered again.
                log.debug("The {} '{}' went away between the index and the fetch.", subject, key);
                return GovernanceResourceFetch.skipped("it went away between the index and the fetch");
            }
            throw e;
        }
        if (answer.isNotModified()) {
            if (knownEtag == null || knownEtag.isBlank()) {
                // Nothing was asked conditionally, so there is nothing stored for this to confirm.
                log.warn("The {} '{}' answered \"not modified\" to an unconditional request. It is not "
                         + "replicated.", subject, key);
                return GovernanceResourceFetch.skipped("it answered \"not modified\" to an unconditional request");
            }
            return GovernanceResourceFetch.unchanged();
        }
        if (answer.isRedirect()) {
            log.warn("The {} '{}' answered with a redirect to {}, which is not followed. It is not replicated.",
                    subject, key, answer.location());
            return GovernanceResourceFetch.skipped("it answered with a redirect, which is not followed");
        }
        if (answer.tooLarge()) {
            log.warn("The {} '{}' is larger than the {} this service stores. It is not replicated.", subject,
                    key, properties.getMaxResourceSize());
            return GovernanceResourceFetch.skipped(
                    "it is larger than the %s this service stores".formatted(properties.getMaxResourceSize()));
        }
        String etag = answer.etag();
        if (etag == null || etag.isBlank()) {
            log.warn("The {} '{}' arrived without an entity tag. It is not replicated.", subject, key);
            return GovernanceResourceFetch.skipped("it arrived without an entity tag");
        }
        byte[] bytes = answer.body() == null ? new byte[0] : answer.body();
        return GovernanceResourceFetch.stored(
                new GovernanceResource(kind, key, etag, bytes, bytes.length, clock.instant()));
    }

    private void requireConfigured() {
        if (!clients.isConfigured()) {
            throw new GovernanceUnavailableException("No Governance Service is configured.");
        }
    }

    private UpstreamHttp.Answer call(Supplier<UpstreamHttp.Answer> request) {
        try {
            return request.get();
        } catch (UpstreamException e) {
            throw new GovernanceUnavailableException(("The Governance Service at %s could not be read: %s")
                    .formatted(clients.url().orElse(""), e.getMessage()), e);
        } catch (RuntimeException e) {
            throw new GovernanceUnavailableException(("The Governance Service at %s could not be reached: %s")
                    .formatted(clients.url().orElse(""), e.getMessage()), e);
        }
    }

    private static String systemPath(String systemName) {
        return SYSTEMS_INDEX_PATH + "/" + UriUtils.encodePathSegment(systemName, StandardCharsets.UTF_8);
    }

    private static String rulePath(String ruleId) {
        return RULES_INDEX_PATH + "/" + UriUtils.encodePathSegment(ruleId, StandardCharsets.UTF_8);
    }
}
