package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.ReactionNetworkPublication;
import ch.admin.bit.jeap.doc.domain.architecture.PublishedReactions;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Publishes each environment's graph once, as part of the shell's atomic publication. */
@Component
@RequiredArgsConstructor
public class ReactionNetworkFiles {
    public static final String FILE_NAME = "reaction-graph.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ReactionNetworkPublication networks;

    public Map<String, Optional<PublishedReactions>> write(Site site, SitePart part, Path contentDirectory) throws IOException {
        Map<String, Optional<PublishedReactions>> snapshots = new LinkedHashMap<>();
        for (SiteEnvironment environment : site.environments()) {
            if (!part.carries(environment.id())) {
                continue;
            }
            var snapshot = networks.read(environment.id());
            snapshots.put(environment.id(), snapshot);
            if (part.carriesWholeEnvironments()) {
                writeSnapshot(site, environment, snapshot, contentDirectory);
            }
        }
        return Map.copyOf(snapshots);
    }

    private static void writeSnapshot(Site site, SiteEnvironment environment,
                                      Optional<PublishedReactions> snapshot, Path contentDirectory) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("formatVersion", 1);
        payload.put("site", site.id());
        payload.put("environment", environment.id());
        payload.put("available", snapshot.isPresent());
        snapshot.ifPresent(value -> {
            payload.put("identityScheme", value.observations().identityScheme());
            payload.put("graph", ReactionGraphJson.of(value.observations()));
            payload.put("ownership", value.ownership());
            payload.put("messageOwnership", value.messageOwnership());
            payload.put("ownershipComplete", value.ownershipComplete());
        });
        // Hash content before adding provenance: an identical graph fetched again must not
        // change the snapshot hash, which ContentDigest cannot normalize like a timestamp.
        payload.put("snapshot", digest(JSON.writeValueAsBytes(payload)));
        snapshot.ifPresent(value -> payload.put("importedAt", value.importedAt().toString()));
        Path directory = contentDirectory.resolve("static" + environment.routePrefix());
        Files.createDirectories(directory);
        Files.write(directory.resolve(FILE_NAME), JSON.writeValueAsBytes(payload));
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
