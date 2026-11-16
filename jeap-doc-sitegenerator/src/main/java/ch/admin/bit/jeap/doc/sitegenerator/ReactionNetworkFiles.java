package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.ReactionNetworkPublication;
import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.architecture.PublishedReactions;
import ch.admin.bit.jeap.doc.domain.template.ReactionDocumentationLinks;
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
import java.util.Optional;

/** Publishes each environment's graph once, as part of the shell's atomic publication. */
@Component
@RequiredArgsConstructor
public class ReactionNetworkFiles {
    public static final String FILE_NAME = "reaction-graph.json";
    public static final String EXPLORER_STATUS_FILE = "reaction-explorer.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ReactionNetworkPublication networks;

    public Map<String, Optional<PublishedReactions>> read(Site site, SitePart part) {
        Map<String, Optional<PublishedReactions>> snapshots = new LinkedHashMap<>();
        for (SiteEnvironment environment : site.environments()) {
            if (!part.carries(environment.id())) {
                continue;
            }
            var snapshot = networks.read(environment.id());
            snapshots.put(environment.id(), snapshot);
        }
        return Map.copyOf(snapshots);
    }

    public void write(Site site, SitePart part, Path contentDirectory,
                      Map<String, Optional<PublishedReactions>> snapshots, Map<String, EnvironmentModel> models) throws IOException {
        if (!part.carriesWholeEnvironments()) return;
        for (SiteEnvironment environment : site.environments()) {
            if (!part.carries(environment.id())) continue;
            EnvironmentModel model = models.get(environment.id());
            writeSnapshot(site, environment, snapshots.getOrDefault(environment.id(), Optional.empty()),
                    model == null ? ReactionDocumentationLinks.empty() : model.reactionLinks(), contentDirectory);
        }
    }

    private static void writeSnapshot(Site site, SiteEnvironment environment,
                                      Optional<PublishedReactions> snapshot, ReactionDocumentationLinks links, Path contentDirectory) throws IOException {
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
            payload.put("documentation", links);
        });
        // Hash content before adding provenance: an identical graph fetched again must not
        // change the snapshot hash, which ContentDigest cannot normalize like a timestamp.
        payload.put("snapshot", digest(JSON.writeValueAsBytes(payload)));
        snapshot.ifPresent(value -> payload.put("importedAt", value.importedAt().toString()));
        Path directory = contentDirectory.resolve("static" + environment.routePrefix());
        Files.createDirectories(directory);
        Files.write(directory.resolve(FILE_NAME), JSON.writeValueAsBytes(payload));
        // Published atomically with this shell's Explorer route and snapshot. Older shells have no marker.
        Path explorerStatus = directory.resolve(EXPLORER_STATUS_FILE);
        if (snapshot.isPresent()) {
            Files.write(explorerStatus, JSON.writeValueAsBytes(new ExplorerAvailability(1, site.id(), environment.id(), true)));
        } else {
            // Leave sites without reactions unchanged, and never retain a marker from a previous preparation.
            Files.deleteIfExists(explorerStatus);
        }
    }

    private record ExplorerAvailability(int formatVersion, String site, String environment, boolean available) {}

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
