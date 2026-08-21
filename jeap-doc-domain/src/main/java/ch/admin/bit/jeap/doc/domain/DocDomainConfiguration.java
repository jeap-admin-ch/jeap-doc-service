package ch.admin.bit.jeap.doc.domain;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Registers the domain services of the doc service.
 * <p>
 * The domain module holds the business logic and the ports it needs; the adapters are wired to those ports by
 * the auto-configuration of the adapter modules.
 */
@AutoConfiguration
@ComponentScan
public class DocDomainConfiguration {
}
