# API

The doc service's REST API lives under `/api` and is authenticated with a bearer token.

The API is described with OpenAPI. The jEAP Swagger starter serves the description at `/api-docs` and the Swagger
UI at `/swagger-ui.html`; both are switched off unless the instance sets `jeap.swagger.status` (see
[Configuration](configuration.md)).

Everything below `/api/uploads` is an upload, and the segment after it says **what kind of thing is uploaded**.
Documentation is the first kind; another kind - assets a documentation site needs next to its Markdown - would get
its own segment rather than a parameter on this endpoint.

## Uploading a documentation set

```
PUT /api/uploads/docs/{uploadId}
Content-Type: application/zip
Content-Length: 184320
Authorization: Bearer ...
```

The body is the ZIP archive of the documentation set. Everything that describes it travels as query parameters,
named like the keys of the doc workflow configuration a repository writes, so the workflow passes its
configuration through instead of translating it.

`{uploadId}` is a UUID the client chooses, and it is the **idempotency key**: repeating an upload under the same
id never publishes a second documentation set - see [Uploads](uploads.md#idempotency-what-a-retry-does).

**`Content-Length` is mandatory.** It lets a bundle that is too large be rejected before it is transferred, it is
what the object storage is told to expect, and it is what a body cut short is recognised by; a request without it
is answered with `411`. Every client that uploads a file sends it (`curl --data-binary @file` and `curl -T file`
do).

### Parameters

| Parameter           | Required                             | Value                                                            | Example                                                  |
| ------------------- | ------------------------------------ | ---------------------------------------------------------------- | -------------------------------------------------------- |
| `{uploadId}` (path) | yes                                  | UUID the client chooses, so an upload can be retried under it    | `8f1c9a2e-6a1a-4a5f-9a5e-2b0f9a3c1d77`                   |
| `site`              | no                                   | Documentation site the documents belong to                       | `dazit`                                                  |
| `type`              | yes                                  | `system-docs`, `component-docs` or `library-docs`                | `component-docs`                                         |
| `system`            | yes                                  | System the documents belong to; the write role is checked for it | `wvs`                                                    |
| `component`         | for `component-docs`                 | Component the documents belong to                                | `foo-bar-scs`                                            |
| `library`           | for `library-docs`                   | Library the documents belong to                                  | `wvs-common-lib`                                         |
| `template`          | yes                                  | Section catalog the documents follow                             | `arc42`                                                  |
| `source-format`     | yes                                  | `markdown` or `html`                                             | `markdown`                                               |
| `location`          | for `source-format=html`             | Section the documents are embedded in                            | `6-runtime-view`                                         |
| `topic`             | for `source-format=html`             | Slug identifying the documents within their section              | `spring-rest-docs`                                       |
| `label`             | for `source-format=html`             | Menu label of the documents                                      | `Spring REST Docs`                                       |
| `source-repository` | yes                                  | Repository the documents came from                               | `ssh://git@bitbucket.example.ch/wvs/foo-bar-scs.git`     |
| `source-revision`   | yes                                  | Commit the documents were built from                             | `9a1c2f8`                                                |
| `source-ref`        | yes                                  | Branch or tag that was built                                     | `main`                                                   |
| `source-timestamp`  | yes                                  | Timestamp of that commit, ISO-8601                               | `2026-08-21T09:12:00+02:00`                              |
| `version`           | for `component-docs`, `library-docs` | Version of the component or library                              | `1.4.0`                                                  |
| `build-url`         | no                                   | Build that uploaded the documents                                | `https://github.com/wvs/foo-bar-scs/actions/runs/1234567890` |
| `generated-at`      | no                                   | When the documents were generated, ISO-8601                      | `2026-08-21T09:15:00+02:00`                              |

The doc service will host more than one documentation site. An upload without a `site` targets the default site,
which is what a repository that does not care about sites sends.

`component` and `library` name the owner of the documents and belong to their type: a `system-docs` upload names
neither, a `component-docs` upload names a component, a `library-docs` upload a library. `system`, `component`,
`library`, `site`, `template`, `location` and `topic` are slugs - lower case letters, digits and single hyphens.

### The result

A request that stored a bundle **created** the upload and is answered with `201`; a request repeating an upload
that was already stored changed nothing and is answered with `200`. The body is the same, and it is the same body
in both cases - the result of the attempt whose bundle lies in the storage.

```json
{
  "uploadId": "8f1c9a2e-6a1a-4a5f-9a5e-2b0f9a3c1d77",
  "id": 42,
  "state": "PENDING",
  "sizeInBytes": 184320,
  "receivedAt": "2026-08-24T07:12:00.123Z"
}
```

`id` is the identifier the doc service gave the upload, and the path its bundle is stored under - a build log
naming it is enough to find the bundle again. `PENDING` means the bundle is stored and waiting for the
documentation generator; the states are described in [Uploads](uploads.md#the-state-of-an-upload).

## Reading the state of an upload

```
GET /api/uploads/docs/{uploadId}?system=wvs
Authorization: Bearer ...
```

Answers what became of an upload - after a retry, or after an answer that never arrived. The `system` parameter
names the system the upload belongs to and is what the write role is checked against; an upload of another system
is answered with `404`.

```json
{
  "uploadId": "8f1c9a2e-6a1a-4a5f-9a5e-2b0f9a3c1d77",
  "id": 42,
  "state": "PENDING",
  "type": "component-docs",
  "system": "wvs",
  "component": "foo-bar-scs",
  "template": "arc42",
  "sizeInBytes": 184320,
  "attempt": 1,
  "receivedAt": "2026-08-24T07:12:00.123Z",
  "completedAt": "2026-08-24T07:12:03.456Z"
}
```

### Examples

Markdown documentation of a component:

```bash
curl -X PUT "https://docs.example.ch/api/uploads/docs/$(uuidgen)?type=component-docs&system=wvs\
&component=foo-bar-scs&template=arc42&source-format=markdown&version=1.4.0\
&source-repository=ssh://git@bitbucket.example.ch/wvs/foo-bar-scs.git&source-revision=9a1c2f8&source-ref=main\
&source-timestamp=2026-08-21T09:12:00%2B02:00" \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/zip" \
     --data-binary @docs.zip
```

HTML documentation a build generated, embedded into a section of the template:

```bash
curl -X PUT "https://docs.example.ch/api/uploads/docs/$(uuidgen)?type=component-docs&system=wvs\
&component=foo-bar-scs&template=arc42&source-format=html&location=6-runtime-view&topic=spring-rest-docs\
&label=Spring+REST+Docs&version=1.4.0\
&source-repository=ssh://git@bitbucket.example.ch/wvs/foo-bar-scs.git&source-revision=9a1c2f8&source-ref=main\
&source-timestamp=2026-08-21T09:12:00%2B02:00" \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/zip" \
     --data-binary @docs.zip
```

### Responses

| Status | Meaning                                                                                 |
| ------ | ---------------------------------------------------------------------------------------- |
| `201`  | The bundle was stored                                                                    |
| `200`  | The upload had already been stored under the same upload id; nothing was changed         |
| `400`  | The parameters do not describe a documentation set, or the body is not as long as announced |
| `401`  | No or invalid token                                                                      |
| `403`  | The token does not grant the write role for the given system                             |
| `404`  | No such upload of that system (`GET`)                                                    |
| `409`  | Another attempt of this upload is being received, or the upload id belongs to a different upload |
| `411`  | The upload does not announce its size in `Content-Length`                                 |
| `413`  | The bundle is larger than the accepted size                                              |
| `415`  | The body is not a ZIP archive                                                            |
| `500`  | The bundle could not be stored; the upload is recorded as failed and can be retried      |

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

| Code                      | Meaning                                                                            |
| ------------------------- | ------------------------------------------------------------------------------------ |
| `MISSING_PARAMETER`       | A parameter the other parameters make mandatory is missing                         |
| `UNKNOWN_PARAMETER`       | A parameter the service does not know was sent - most likely a typo                |
| `INVALID_PARAMETER_VALUE` | A parameter carries a value the service cannot use                                 |
| `LENGTH_REQUIRED`         | The request announces no `Content-Length`                                          |
| `CONTENT_LENGTH_MISMATCH` | The body is not as long as `Content-Length` announced - see the note below         |
| `SIZE_LIMIT_EXCEEDED`     | The bundle is larger than `jeap.doc.upload.max-size`                               |
| `UPLOAD_IN_PROGRESS`      | Another attempt of this upload is being received; the answer carries `Retry-After` |
| `UPLOAD_ID_CONFLICT`      | The upload id was already used for an upload that describes something else         |
| `STORAGE_FAILED`          | The bundle could not be stored - the upload is recorded as failed and can be retried |

A body that ends before its announced length is rejected with `400`, but usually **not** with a problem document:
the servlet container notices the connection ending early and answers first. The service records the upload as
failed with `CONTENT_LENGTH_MISMATCH` either way, so what happened is visible in its state and its log. An upload
that overshoots the accepted size by a wide margin can end the same way: the rest of the request has to be read
before the `413` can be answered, and the service reads only so much of a body nobody asked for.

An unknown parameter is rejected rather than ignored: a typo in a workflow configuration must fail loudly instead
of silently publishing something else than the repository intended.

## Related

- [Uploads](uploads.md)
- [Architecture](architecture.md)
- [Security](security.md)
