package ch.admin.bit.jeap.doc.sitegenerator;

import ch.admin.bit.jeap.doc.domain.Site;
import ch.admin.bit.jeap.doc.domain.SiteEnvironment;
import ch.admin.bit.jeap.doc.domain.SitePart;
import ch.admin.bit.jeap.doc.domain.ReactionNetworkPublication;
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

    public void write(Site site, SitePart part, Path contentDirectory) throws IOException {
        if (!part.carriesWholeEnvironments()) {
            return;
        }
        for (SiteEnvironment environment : site.environments()) {
            if (!part.carries(environment.id())) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("formatVersion", 1);
            payload.put("site", site.id());
            payload.put("environment", environment.id());
            var snapshot = networks.read(environment.id());
            payload.put("available", snapshot.isPresent());
            snapshot.ifPresent(value -> {
                payload.put("identityScheme", value.graph().identityScheme());
                payload.put("importedAt", value.importedAt().toString());
                payload.put("graph", value.graph());
                payload.put("ownership", value.ownership());
                payload.put("ownershipComplete", value.ownershipComplete());
            });
            payload.put("snapshot", digest(JSON.writeValueAsBytes(payload)));
            Path directory = contentDirectory.resolve("static" + environment.routePrefix());
            Files.createDirectories(directory);
            Files.write(directory.resolve(FILE_NAME), JSON.writeValueAsBytes(payload));
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
