package ch.admin.bit.jeap.doc.sitegenerator;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The version of the doc service, as the generated documentation names it.
 * <p>
 * Read from a Maven-filtered resource of this module, once, while the class is loaded. <b>Not from Spring
 * Boot's {@code build-info}</b>: an instance repackages its own jar, so that would report the version of the
 * instance rather than of the service whose site generator wrote the page - and not from {@code GitProperties}
 * either, which on this classpath reports the commit of whichever starter contributed one.
 * <p>
 * A version that cannot be read is not worth a failed build: the page then says nothing about it, which is one
 * row of a table rather than a site nobody can publish.
 */
@Slf4j
final class DocServiceVersion {

    private static final String RESOURCE = "jeap-doc-version.properties";
    private static final String VERSION = read();

    private DocServiceVersion() {
    }

    /** The version of the doc service, or null where the resource is missing or was never filtered. */
    static String get() {
        return VERSION;
    }

    private static String read() {
        try (InputStream resource = DocServiceVersion.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) {
                log.warn("{} is not on the classpath, so the generated documentation cannot name the version "
                         + "of the doc service that wrote it.", RESOURCE);
                return null;
            }
            Properties properties = new Properties();
            properties.load(resource);
            String version = properties.getProperty("version");
            // An unfiltered resource still carries the placeholder. Saying nothing is better than printing it.
            return version == null || version.isBlank() || version.startsWith("@") ? null : version;
            // RuntimeException as well as IOException: Properties.load throws IllegalArgumentException on a
            // malformed escape, and out of a static initialiser that becomes an ExceptionInInitializerError -
            // after which every build of every site fails on a class that cannot be loaded. One row of one
            // table is not worth that.
        } catch (IOException | RuntimeException e) {
            log.warn("{} could not be read, so the generated documentation cannot name the version of the doc "
                     + "service that wrote it.", RESOURCE, e);
            return null;
        }
    }
}
