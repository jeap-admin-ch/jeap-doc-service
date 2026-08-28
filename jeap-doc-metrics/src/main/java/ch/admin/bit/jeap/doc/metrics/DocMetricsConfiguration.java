package ch.admin.bit.jeap.doc.metrics;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * The metrics adapter: it implements the metrics ports of the domain with Micrometer.
 * <p>
 * A module of its own so that the domain does not depend on a metrics library. What the domain says is
 * <i>an upload was stored, a build failed</i>; how that becomes a meter, what its name and tags are and how the
 * gauges are bound is decided here, beside the registry that receives them.
 */
@AutoConfiguration
@ComponentScan
public class DocMetricsConfiguration {
}
