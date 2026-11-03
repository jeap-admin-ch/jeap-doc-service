package ch.admin.bit.jeap.doc.domain;

import ch.admin.bit.jeap.doc.domain.content.ContentSources;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Cuts each site the way its content source does. It is the partition everything else asks: the builds, the
 * publications, the serving and the triggers work on parts without knowing which source a site has.
 */
@Service
@RequiredArgsConstructor
public class ContentSourcePartition implements SitePartition {

    private final ContentSources sources;

    @Override
    public String axis(Site site) {
        return partitionOf(site).axis(site);
    }

    @Override
    public List<SitePart> partsOf(Site site) {
        return partitionOf(site).partsOf(site);
    }

    @Override
    public Optional<SitePart> partOf(Site site, String partId) {
        return partitionOf(site).partOf(site, partId);
    }

    @Override
    public SitePart shellOf(Site site) {
        return partitionOf(site).shellOf(site);
    }

    @Override
    public List<SitePart> partsDocumenting(Site site, String environment, String systemName) {
        return partitionOf(site).partsDocumenting(site, environment, systemName);
    }

    private SitePartition partitionOf(Site site) {
        return sources.of(site).partition();
    }
}
