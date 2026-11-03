package ch.admin.bit.jeap.doc.governance;

import ch.admin.bit.jeap.doc.upstream.UpstreamClientSettings;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Where the Governance Service is, and how it is read.
 * <p>
 * Instance-wide and not per-environment: unlike the architecture model, a landscape has one Governance Service
 * and the governance site has {@code per-environment: false}. The import's schedule is
 * {@code jeap.doc.governance.import.cron} - a property of the domain, not of this adapter, so it is
 * {@code GovernanceImportProperties} in {@code jeap-doc-domain}, the same split {@code ArchRepoProperties} and
 * {@code ArchitectureImportProperties} make.
 */
@Data
@ConfigurationProperties("jeap.doc.governance")
public class GovernanceProperties {

    /**
     * Where the Governance Service is, without a path below its context path - the <b>internal</b> host on a
     * platform that gives a service two of them, because this is a service calling a service and not a
     * browser following a link. Null when no Governance Service is configured for this instance.
     */
    private String url;

    /** A blank URL - what {@code ${GOVERNANCE_URL:}} gives - is no URL, so it reads as not configured. */
    public void setUrl(String url) {
        this.url = url == null || url.isBlank() ? null : url;
    }

    /**
     * The Spring Security OAuth2 client registration the token is obtained with. The client needs the role
     * {@code governance-documentation} / {@code read} on the Governance Service's authorization server, which
     * is not this service's own.
     */
    private String clientRegistration;

    /**
     * What the client does when the Governance Service is slow.
     */
    private UpstreamClientSettings client = new UpstreamClientSettings();

    /**
     * The largest index or resource body this service stores. The governance reports are a few hundred systems
     * and rules of plain JSON, nothing like an OpenAPI specification, so the default is generous rather than
     * measured.
     */
    private DataSize maxResourceSize = DataSize.ofMegabytes(1);
}
