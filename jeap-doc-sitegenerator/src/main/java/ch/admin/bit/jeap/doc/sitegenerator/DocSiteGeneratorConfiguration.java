package ch.admin.bit.jeap.doc.sitegenerator;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * The site generator adapter: it turns what the domain wrote into a static site, by running the site generator
 * over a workspace it prepares.
 * <p>
 * The technology - Docusaurus, Node, npm - lives in this module and nowhere else. The domain knows only that
 * something can produce a site out of a directory of content.
 */
@AutoConfiguration
@ComponentScan
public class DocSiteGeneratorConfiguration {
}
