# API

The doc service's REST API lives under `/api` and is authenticated with a bearer token.

The API is described with OpenAPI. The jEAP Swagger starter serves the description at `/api-docs` and the Swagger
UI at `/swagger-ui.html`; both are switched off unless the instance sets `jeap.swagger.status` (see
[Configuration](configuration.md)).

## Uploading a documentation set

```
PUT /api/docs/uploads/{uploadId}
Content-Type: application/zip
Authorization: Bearer ...
```

The body is the ZIP archive of the documentation set. Everything that describes it travels as query parameters,
named like the keys of the doc workflow configuration a repository writes, so the workflow passes its
configuration through instead of translating it.

### Parameters

| Parameter           | Required                             | Value                                                            |
| ------------------- | ------------------------------------ | ---------------------------------------------------------------- |
| `{uploadId}` (path) | yes                                  | UUID the client chooses, so an upload can be retried under it    |
| `type`              | yes                                  | `system-docs`, `component-docs` or `library-docs`                |
| `system`            | yes                                  | System the documents belong to; the write role is checked for it |
| `component`         | for `component-docs`                 | Component the documents belong to                                |
| `library`           | for `library-docs`                   | Library the documents belong to                                  |
| `template`          | yes                                  | Section catalog the documents follow, e.g. `arc42` or `bmad`     |
| `source-format`     | yes                                  | `markdown` or `html`                                             |
| `location`          | for `source-format=html`             | Section the documents are embedded in, e.g. `6-runtime-view`     |
| `topic`             | for `source-format=html`             | Slug identifying the documents within their section              |
| `label`             | for `source-format=html`             | Menu label of the documents, e.g. `Spring REST Docs`             |
| `source-repository` | yes                                  | Repository the documents came from                               |
| `source-revision`   | yes                                  | Commit the documents were built from                             |
| `source-ref`        | yes                                  | Branch or tag that was built                                     |
| `source-timestamp`  | yes                                  | Timestamp of that commit, ISO-8601                               |
| `version`           | for `component-docs`, `library-docs` | Version of the component or library                              |
| `build-url`         | no                                   | Build that uploaded the documents                                |
| `generated-at`      | no                                   | When the documents were generated, ISO-8601                      |

`component` and `library` name the owner of the documents and belong to their type: a `system-docs` upload names
neither, a `component-docs` upload names a component, a `library-docs` upload a library. `system`, `component`,
`library`, `template`, `location` and `topic` are slugs - lower case letters, digits and single hyphens.

### Examples

Markdown documentation of a component:

```bash
curl -X PUT "https://docs.example.ch/api/docs/uploads/$(uuidgen)?type=component-docs&system=wvs\
&component=foo-bar-scs&template=arc42&source-format=markdown&version=1.4.0\
&source-repository=ssh://git@bitbucket.example.ch/wvs/foo-bar-scs.git&source-revision=9a1c2f8&source-ref=main\
&source-timestamp=2026-08-21T09:12:00%2B02:00" \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/zip" \
     --data-binary @docs.zip
```

HTML documentation a build generated, embedded into a section of the template:

```bash
curl -X PUT "https://docs.example.ch/api/docs/uploads/$(uuidgen)?type=component-docs&system=wvs\
&component=foo-bar-scs&template=arc42&source-format=html&location=6-runtime-view&topic=spring-rest-docs\
&label=Spring+REST+Docs&version=1.4.0\
&source-repository=ssh://git@bitbucket.example.ch/wvs/foo-bar-scs.git&source-revision=9a1c2f8&source-ref=main\
&source-timestamp=2026-08-21T09:12:00%2B02:00" \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/zip" \
     --data-binary @docs.zip
```

### Responses

| Status | Meaning                                                      |
| ------ | ------------------------------------------------------------ |
| `200`  | The upload was accepted                                      |
| `400`  | The parameters do not describe a documentation set           |
| `401`  | No or invalid token                                          |
| `403`  | The token does not grant the write role for the given system |
| `415`  | The body is not a ZIP archive                                |

A rejected upload answers with an RFC 9457 problem document that names the reason in its `code` member, so a
workflow can tell a misconfigured upload from a failing service:

```json
{
  "type": "https://jeap.admin.ch/problems/docs/invalid-upload",
  "title": "The upload does not describe a documentation set",
  "status": 400,
  "detail": "The parameter 'version' is required for component documentation.",
  "code": "MISSING_PARAMETER"
}
```

| Code                      | Meaning                                                             |
| ------------------------- | ------------------------------------------------------------------- |
| `MISSING_PARAMETER`       | A parameter the other parameters make mandatory is missing          |
| `UNKNOWN_PARAMETER`       | A parameter the service does not know was sent - most likely a typo |
| `INVALID_PARAMETER_VALUE` | A parameter carries a value the service cannot use                  |

An unknown parameter is rejected rather than ignored: a typo in a workflow configuration must fail loudly instead
of silently publishing something else than the repository intended.

## Related

- [Architecture](architecture.md)
- [Security](security.md)
