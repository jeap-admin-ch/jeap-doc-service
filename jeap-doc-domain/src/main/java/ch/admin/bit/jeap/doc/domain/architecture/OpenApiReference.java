package ch.admin.bit.jeap.doc.domain.architecture;

/**
 * That a component has published an OpenAPI specification, and where it can be read.
 * <p>
 * Nothing here is rendered yet. It is carried so a component page can say that the component has a REST API
 * before the page showing it exists.
 *
 * @param version    the version the component declares
 * @param serverUrl  where the API is served
 * @param contentUrl where the specification is read from, relative to the architecture repository
 * @param swaggerUrl the Swagger UI deep link, absolute because a browser follows it
 */
public record OpenApiReference(String version, String serverUrl, String contentUrl, String swaggerUrl) {
}
