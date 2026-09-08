package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * That a component has published an OpenAPI specification, and where it can be read.
 * <p>
 * The reference, not the specification. {@code swaggerUrl} is what the component's REST API page links to;
 * the version and the server URL are what it falls back to when nothing has been replicated. The groups and
 * the operations come from the replicated copy, in {@link ComponentArtifacts}.
 *
 * @param version    the version the component declares
 * @param serverUrl  where the API is served
 * @param contentUrl where the specification is read from, relative to the architecture repository
 * @param swaggerUrl the Swagger UI deep link, absolute because a browser follows it
 */
public record OpenApiReference(String version, String serverUrl, String contentUrl, String swaggerUrl) {
}
