package ch.admin.bit.jeap.doc.archrepo;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

/**
 * The resources of the architecture repository's {@code /docs-api} that an import reads.
 * <p>
 * The three model resources are asked for unconditionally: the upstream computes their entity tag over the
 * serialized body, so answering "not modified" costs it exactly what answering with the body costs. The two
 * indexes are asked for conditionally, because their tags come from a stored hash.
 * <p>
 * The content of an artifact is not here. Its URL comes from the index and is resolved against the origin of
 * the upstream, so it is fetched with the {@link org.springframework.web.client.RestClient} itself.
 */
interface DocsApiClient {

    @GetExchange("/docs-api/systems")
    DocsApiDtos.SystemListDto systems();

    @GetExchange("/docs-api/systems/{system}")
    DocsApiDtos.SystemDetailDto system(@PathVariable("system") String system);

    @GetExchange("/docs-api/systems/{system}/messages")
    DocsApiDtos.MessageListDto messages(@PathVariable("system") String system);
}
