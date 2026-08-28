package ch.admin.bit.jeap.doc.sitegenerator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The site template, as it is packaged: the sources of the site generator's application, read from this
 * service's own classpath.
 * <p>
 * That is deliberate. The template is by definition the version of {@code jeap-doc-site} this service was built
 * against, so there is nothing to keep in step with an image and no directory an instance could point at the
 * wrong thing. The one part that cannot be packaged is {@code node_modules} - it is installed by the image build
 * and symlinked into the workspace rather than copied.
 */
@Slf4j
@Component
public class SiteTemplate {

    /** The directory the template is packaged under, in the jar and in the classes directory alike. */
    static final String ROOT = "site";

    /** The file the two halves of the template are compared by, see {@code requireDependenciesMatch}. */
    static final String LOCKFILE = "package-lock.json";

    /** The one directory generated content is written into, and the one this never overwrites. */
    static final String CONTENT_DIRECTORY = "content";

    static final String NODE_MODULES = "node_modules";

    /** Where the template keeps one stylesheet per colour scheme, named after the scheme a site configures. */
    static final String SCHEMES_DIRECTORY = "src/css/schemes";

    /** What installing the template never touches: the generated content, and the linked dependencies. */
    private static final Set<String> PROTECTED_ENTRIES = Set.of(CONTENT_DIRECTORY, NODE_MODULES);

    /**
     * Installs the template into a workspace that already holds its generated content.
     * <p>
     * <b>Everything but the content is cleared first, and the template then goes on top</b> - so whatever was
     * generated, the application that runs is the template's, byte for byte and at every depth. Copying over the
     * content would not be enough: a copy replaces what it has, so content could still <i>add</i> a file the
     * template does not ship, and {@code src/theme/Root.tsx} is the one that matters, because the site generator
     * picks it up and runs it.
     * <p>
     * Today the only thing writing into a workspace is this service's own generator; from the story that
     * publishes uploaded documentation it will not be, and by then this has to have been true all along.
     */
    public void installInto(Path workspace) throws IOException {
        clearEverythingButContent(workspace, topLevelNames());
        copyInto(workspace);
    }

    /**
     * Removes every top-level entry of the workspace but the generated content and the linked dependencies.
     * Something the template does not own can only be the generator writing where it should not have, so it is
     * said out loud; the template's own entries are replaced without comment.
     */
    private static void clearEverythingButContent(Path workspace, Set<String> templateEntries) throws IOException {
        if (!Files.isDirectory(workspace)) {
            return;
        }
        try (Stream<Path> entries = Files.list(workspace)) {
            List<Path> removable = entries
                    .filter(entry -> !PROTECTED_ENTRIES.contains(entry.getFileName().toString()))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path entry : removable) {
                String name = entry.getFileName().toString();
                if (!templateEntries.contains(name)) {
                    log.warn("Removing {} from the build workspace: it is neither generated content nor part of "
                             + "the site template, and nothing but the two may take part in the build.", name);
                }
                FileSystemUtils.deleteRecursively(entry);
            }
        }
    }

    /**
     * Copies every file of the template into the given directory, and reports their paths within the template.
     */
    List<String> copyInto(Path target) throws IOException {
        URL marker = resourceUrl(ROOT + "/package.json");
        return "jar".equals(marker.getProtocol()) ? copyFromJar(marker, target) : copyFromDirectory(marker, target);
    }

    /**
     * Reads one file of the template as text - the lockfile, to compare it against the one the image installed
     * from.
     */
    String read(String path) throws IOException {
        try (InputStream content = new ClassPathResource(ROOT + "/" + path).getInputStream()) {
            return new String(content.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The colour schemes this template actually ships, by the name a site configures.
     * <p>
     * Read from the files rather than from a list kept somewhere: a name with no stylesheet behind it fails the
     * Docusaurus build minutes into a run, and a list maintained by hand is exactly the thing that drifts away
     * from the directory it describes.
     */
    Set<String> colorSchemes() throws IOException {
        Resource[] stylesheets = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:" + ROOT + "/" + SCHEMES_DIRECTORY + "/*.css");
        return Arrays.stream(stylesheets)
                .map(Resource::getFilename)
                .filter(Objects::nonNull)
                .map(name -> name.substring(0, name.length() - ".css".length()))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static URL resourceUrl(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IOException(("The site template is not on the classpath: %s is missing. The doc service "
                                   + "reads its template from the jeap-doc-site artifact.").formatted(path));
        }
        return resource.getURL();
    }

    /**
     * The packaged case: the template lies in a jar, and the entries under {@code site/} are copied out of it.
     */
    private static List<String> copyFromJar(URL marker, Path target) throws IOException {
        List<String> copied = new ArrayList<>();
        forEachJarEntry(marker, (relative, content) -> {
            Path file = resolveSafely(target, relative);
            Files.createDirectories(file.getParent());
            Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);
            copied.add(relative);
        });
        return copied;
    }

    /**
     * Hands every file of the template under {@code site/} to the given reader, as a path relative to that root.
     */
    private static void forEachJarEntry(URL marker, JarEntryReader reader) throws IOException {
        URLConnection connection = marker.openConnection();
        if (!(connection instanceof JarURLConnection jarConnection)) {
            throw new IOException("The site template is packaged in a way this cannot read: " + marker);
        }
        String prefix = ROOT + "/";
        // Not closed: the connection may be caching the jar for the rest of the JVM, and closing a cached jar
        // file breaks every later read of it.
        JarFile jar = jarConnection.getJarFile();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith(prefix) || entry.isDirectory()) {
                continue;
            }
            try (InputStream content = jar.getInputStream(entry)) {
                reader.read(name.substring(prefix.length()), content);
            }
        }
    }

    /** What {@link #forEachJarEntry} hands one entry to. */
    @FunctionalInterface
    private interface JarEntryReader {
        void read(String relativePath, InputStream content) throws IOException;
    }

    /** Where the template lies when it is not packaged, as it is not while the service is being built. */
    private static Path directoryRoot(URL marker) throws IOException {
        try {
            return Path.of(marker.toURI()).getParent();
        } catch (java.net.URISyntaxException e) {
            throw new IOException("The site template is at a location this cannot read: " + marker, e);
        }
    }

    /**
     * The unpackaged case: the template lies in a classes directory, as it does while the service is built.
     */
    private static List<String> copyFromDirectory(URL marker, Path target) throws IOException {
        Path root = directoryRoot(marker);
        List<String> copied = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String relative = root.relativize(file).toString().replace('\\', '/');
                try {
                    Path destination = resolveSafely(target, relative);
                    Files.createDirectories(destination.getParent());
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    copied.add(relative);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return copied;
    }

    /**
     * The names of the template's own top-level entries, so that clearing the workspace can tell what it is
     * replacing from what it is removing.
     */
    private Set<String> topLevelNames() throws IOException {
        URL marker = resourceUrl(ROOT + "/package.json");
        Set<String> names = new LinkedHashSet<>();
        if ("jar".equals(marker.getProtocol())) {
            forEachJarEntry(marker, (name, ignored) -> names.add(name.split("/", 2)[0]));
        } else {
            try (Stream<Path> entries = Files.list(directoryRoot(marker))) {
                entries.forEach(entry -> names.add(entry.getFileName().toString()));
            }
        }
        return names;
    }

    /**
     * Resolves a path from an archive against the target directory, refusing anything that would leave it.
     */
    private static Path resolveSafely(Path target, String relative) throws IOException {
        Path resolved = target.resolve(relative).normalize();
        if (!resolved.startsWith(target.normalize())) {
            throw new IOException("The site template contains an entry that would be written outside the "
                                  + "workspace: " + relative);
        }
        return resolved;
    }
}
