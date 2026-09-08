# API

The doc service's REST API lives under `/api` and is authenticated with a bearer token.

The API is described with OpenAPI. The jEAP Swagger starter serves the description at `/api-docs` and the Swagger
UI at `/swagger-ui.html`; both are switched off unless the instance sets `jeap.swagger.status` (see
[Configuration](configuration.md)).

## Overview

| Method | Path                                                  | Role                                     | Answers      | What it does                                                                                                |
|--------|-------------------------------------------------------|------------------------------------------|--------------|-------------------------------------------------------------------------------------------------------------|
| `PUT`  | `/api/uploads/docs/{uploadId}`                        | `<system-name>_%<system>_@uploads_#write` | `201`, `200` | Stores a documentation set - [Uploading a documentation set](#uploading-a-documentation-set)                |
| `GET`  | `/api/uploads/docs/{uploadId}?system=`                | `<system-name>_%<system>_@uploads_#write` | `200`        | The state of one upload - [Reading the state of an upload](#reading-the-state-of-an-upload)                 |
| `POST` | `/api/uploads/docs/validation`                        | `<system-name>_%<system>_@uploads_#write` | `200`, `422` | Whether a path tree would be accepted - [Validating a documentation set](#validating-a-documentation-set)   |
| `POST` | `/api/sites/{site}/builds`                            | `<system-name>_@sites_#admin`             | `202`        | Asks for the site to be published - [Asking for a site to be published](#asking-for-a-site-to-be-published) |
| `POST` | `/api/sites/{site}/parts/{part}/builds`               | `<system-name>_@sites_#admin`             | `202`        | Asks for one part to be published - [Asking for one part to be published](#asking-for-one-part-to-be-published) |
| `GET`  | `/api/sites`                                          | `<system-name>_@sites_#read`              | `200`        | The state of every configured site - [Reading the state of the sites](#reading-the-state-of-the-sites)     |
| `GET`  | `/api/sites/{site}`                                   | `<system-name>_@sites_#read`              | `200`        | The state of one site - [Reading the state of the sites](#reading-the-state-of-the-sites)                   |
| `GET`  | `/api/sites/{site}/parts`                             | `<system-name>_@sites_#read`              | `200`        | The parts of a site and their state - [Reading the parts](#reading-the-parts)                               |
| `GET`  | `/api/sites/{site}/parts/{part}/builds`               | `<system-name>_@sites_#read`              | `200`        | The builds of one part - [Reading the parts](#reading-the-parts)                                            |
| `GET`  | `/api/sites/{site}/builds`                            | `<system-name>_@sites_#read`              | `200`        | The builds of a site, newest first - [Reading the builds](#reading-the-builds)                              |
| `GET`  | `/api/sites/{site}/builds/{buildId}`                  | `<system-name>_@sites_#read`              | `200`        | One build - [Reading the builds](#reading-the-builds)                                                       |
| `POST` | `/api/architecture/imports`                           | `<system-name>_@sites_#admin`             | `202`, `429` | Asks for every architecture repository to be imported - [Asking for the architecture repository to be imported](#asking-for-the-architecture-repository-to-be-imported) |
| `POST` | `/api/architecture/environments/{environment}/imports` | `<system-name>_@sites_#admin`             | `202`, `429` | Asks for one environment's import - [Asking for the architecture repository to be imported](#asking-for-the-architecture-repository-to-be-imported) |
| `GET`  | `/api/architecture/environments`                      | `<system-name>_@sites_#read`              | `200`        | What the imports have been doing - [Reading what the imports have been doing](#reading-what-the-imports-have-been-doing) |

Every endpoint answers `401` without a valid token and `403` with a token that lacks the role; the upload role is
checked for the system named in the request. Everything outside `/api` is the documentation site itself.

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

| Parameter           | Required                             | Value                                                                   | Example                                                         |
|---------------------|--------------------------------------|-------------------------------------------------------------------------|-----------------------------------------------------------------|
| `{uploadId}` (path) | yes                                  | UUID the client chooses, so an upload can be retried under it           | `8f1c9a2e-6a1a-4a5f-9a5e-2b0f9a3c1d77`                          |
| `site`              | no                                   | Documentation site the documents belong to, one the instance configures | `governance`                                                    |
| `type`              | yes                                  | `system-docs`, `component-docs` or `library-docs`                       | `component-docs`                                                |
| `system`            | yes                                  | System the documents belong to; the write role is checked for it        | `orders`                                                        |
| `component`         | for `component-docs`                 | Component the documents belong to                                       | `foo-bar-scs`                                                   |
| `library`           | for `library-docs`                   | Library the documents belong to                                         | `orders-common-lib`                                             |
| `template`          | yes                                  | Section catalog the documents follow                                    | `arc42`                                                         |
| `source-format`     | yes                                  | `markdown` or `html`                                                    | `markdown`                                                      |
| `location`          | for `source-format=html`             | Section the documents are embedded in                                   | `6-runtime-view`                                                |
| `topic`             | for `source-format=html`             | Slug identifying the documents within their section                     | `spring-rest-docs`                                              |
| `label`             | for `source-format=html`             | Menu label of the documents                                             | `Spring REST Docs`                                              |
| `source-repository` | yes                                  | Repository the documents came from                                      | `ssh://git@bitbucket.example.ch/orders/foo-bar-scs.git`         |
| `source-revision`   | yes                                  | Commit the documents were built from                                    | `9a1c2f8`                                                       |
| `source-ref`        | yes                                  | Branch or tag that was built                                            | `main`                                                          |
| `source-timestamp`  | yes                                  | Timestamp of that commit, ISO-8601                                      | `2026-08-21T09:12:00+02:00`                                     |
| `version`           | for `component-docs`, `library-docs` | Version of the component or library                                     | `1.4.0`                                                         |
| `build-url`         | no                                   | Build that uploaded the documents                                       | `https://github.com/orders/foo-bar-scs/actions/runs/1234567890` |
| `generated-at`      | no                                   | When the documents were generated, ISO-8601                             | `2026-08-21T09:15:00+02:00`                                     |

The doc service will host more than one documentation site. An upload without a `site` targets the default site,
which is what a repository that does not care about sites sends.

**A `site` the instance does not configure is rejected** (`UNKNOWN_SITE`, and the reason names the sites that do
exist). Which sites there are is configuration, not something the service works out from what has
been uploaded - so a typo is answered rather than stored and published nowhere.

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
GET /api/uploads/docs/{uploadId}?system=orders
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
  "system": "orders",
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
curl -X PUT "https://docs.example.ch/api/uploads/docs/$(uuidgen)?type=component-docs&system=orders\
&component=foo-bar-scs&template=arc42&source-format=markdown&version=1.4.0\
&source-repository=ssh://git@bitbucket.example.ch/orders/foo-bar-scs.git&source-revision=9a1c2f8&source-ref=main\
&source-timestamp=2026-08-21T09:12:00%2B02:00" \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/zip" \
     --data-binary @docs.zip
```

HTML documentation a build generated, embedded into a section of the template:

```bash
curl -X PUT "https://docs.example.ch/api/uploads/docs/$(uuidgen)?type=component-docs&system=orders\
&component=foo-bar-scs&template=arc42&source-format=html&location=6-runtime-view&topic=spring-rest-docs\
&label=Spring+REST+Docs&version=1.4.0\
&source-repository=ssh://git@bitbucket.example.ch/orders/foo-bar-scs.git&source-revision=9a1c2f8&source-ref=main\
&source-timestamp=2026-08-21T09:12:00%2B02:00" \
     -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/zip" \
     --data-binary @docs.zip
```

### Responses

| Status | Meaning                                                                                          |
|--------|--------------------------------------------------------------------------------------------------|
| `201`  | The bundle was stored                                                                            |
| `200`  | The upload had already been stored under the same upload id; nothing was changed                 |
| `400`  | The parameters do not describe a documentation set, or the body is not as long as announced      |
| `401`  | No or invalid token                                                                              |
| `403`  | The token does not grant the write role for the given system                                     |
| `404`  | No such upload of that system (`GET`)                                                            |
| `409`  | Another attempt of this upload is being received, or the upload id belongs to a different upload |
| `411`  | The upload does not announce its size in `Content-Length`                                        |
| `413`  | The bundle is larger than the accepted size                                                      |
| `415`  | The body is not a ZIP archive                                                                    |
| `500`  | The bundle could not be stored; the upload is recorded as failed and can be retried              |

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

| Code                      | Meaning                                                                                                   |
|---------------------------|-----------------------------------------------------------------------------------------------------------|
| `MISSING_PARAMETER`       | A parameter the other parameters make mandatory is missing                                                |
| `UNKNOWN_PARAMETER`       | A parameter the service does not know was sent - most likely a typo                                       |
| `INVALID_PARAMETER_VALUE` | A parameter carries a value the service cannot use                                                        |
| `UNKNOWN_SITE`            | The `site` names a documentation site this instance does not configure; the reason names the ones it does |
| `LENGTH_REQUIRED`         | The request announces no `Content-Length`                                                                 |
| `CONTENT_LENGTH_MISMATCH` | The body is not as long as `Content-Length` announced - see the note below                                |
| `SIZE_LIMIT_EXCEEDED`     | The bundle is larger than `jeap.doc.upload.max-size`                                                      |
| `UPLOAD_IN_PROGRESS`      | Another attempt of this upload is being received; the answer carries `Retry-After`                        |
| `UPLOAD_ID_CONFLICT`      | The upload id was already used for an upload that describes something else                                |
| `STORAGE_FAILED`          | The bundle could not be stored - the upload is recorded as failed and can be retried                      |

A body that ends before its announced length is rejected with `400`, but usually **not** with a problem document:
the servlet container notices the connection ending early and answers first. The service records the upload as
failed with `CONTENT_LENGTH_MISMATCH` either way, so what happened is visible in its state and its log. An upload
that overshoots the accepted size by a wide margin can end the same way: the rest of the request has to be read
before the `413` can be answered, and the service reads only so much of a body nobody asked for.

An unknown parameter is rejected rather than ignored: a typo in a workflow configuration must fail loudly instead
of silently publishing something else than the repository intended.

## Validating a documentation set

**Before a pipeline builds a ZIP**, it can ask whether the path tree would be accepted. Nothing is uploaded,
stored or read: the tree arrives as a list of paths, the endpoint has no side effect, and it never sees a
file's bytes.

```
POST /api/uploads/docs/validation?type=component-docs&system=orders&component=orders-intake
                                 &template=arc42&source-format=markdown
Content-Type: application/json
Authorization: Bearer ...

{ "paths": ["1-intro/goals.md", "5-building-block-view/design-notes.md"] }
```

Below `/api/uploads/docs`, because what is validated is a documentation upload - and the parameter check of
that path guards it, so a typo in a workflow configuration fails as loudly here as on the upload. The role is
the upload one, `<system-name>_%<system>_@uploads_#write`: the caller is the pipeline that is about to upload.

**The parameters are the ones the structure depends on, and no others**: `type`, `system`,
`component`/`library`, `template`, `source-format`, and `location`/`topic` for HTML. `site`, `version`,
`label`, the four `source-*` parameters, `build-url` and `generated-at` are **refused** - a path tree does not
depend on a commit hash, and an endpoint that demanded one to answer a structural question would be answering
a different one.

### The answer

**The verdict is the status line.** There is no `valid` field beside it.

`200 OK` - nothing to report:

```json
{
  "template": "arc42",
  "pathsChecked": 42,
  "pathsIgnored": 2,
  "allowedFolders": ["1-intro", "2-constraints", "…", "12-glossary"],
  "allowedExtensions": ["avif", "gif", "jpeg", "jpg", "md", "png", "svg", "webp"],
  "findings": [],
  "findingsOmitted": 0
}
```

`findings` and `findingsOmitted` are always there, empty and zero on a `200` - so branch on the status line and
not on whether the fields are present.

`422 Unprocessable Content` - the request was understood and its content cannot be processed. The body is the
same RFC 9457 problem document a rejected upload is answered with, served as `application/problem+json` and
carrying the report as extension members:

```json
{
  "type": "https://jeap.admin.ch/problems/docs/structure-invalid",
  "title": "The documentation structure is invalid",
  "status": 422,
  "detail": "2 problems in 42 paths.",
  "template": "arc42",
  "pathsChecked": 42,
  "pathsIgnored": 2,
  "allowedFolders": ["1-intro", "…"],
  "allowedExtensions": ["md", "…"],
  "findings": [
    {
      "code": "UNKNOWN_CHAPTER",
      "path": "4-runtime-view/reactions.md",
      "message": "'4-runtime-view' is not a chapter of arc42. Did you mean '6-runtime-view'?"
    },
    {
      "code": "RESERVED_NAME",
      "path": "5-building-block-view/whitebox-view.md",
      "message": "'whitebox-view' is generated into 5-building-block-view by the doc service. Two documents at one URL fail the build of this part, so rename the page."
    }
  ],
  "findingsOmitted": 0
}
```

So a pipeline branches three ways: `200` publish, `422` print the findings and stop, anything else fail loudly
because the endpoint or the token is wrong. The `200` is `application/json` and the `422` is
`application/problem+json`; an `Accept` of either is answered rather than refused.

| | |
| --- | --- |
| **Every finding, up to a cap** | Ordered so that two runs of one tree print the same list. At most [`max-findings`](configuration.md#uploads) (default 50); `findingsOmitted` says how many were left out, because a truncated list that does not say so is a lie |
| **What is allowed is said once** | `allowedFolders` and `allowedExtensions` come from the template, so a workflow prints them at the end instead of the service repeating twelve folders in every message |
| **`pathsIgnored`** | The files a ZIP carries that nobody wrote - `.DS_Store` and its kind - are dropped before any rule runs, and counted rather than hidden |
| **`path` is absent for a set-level finding** | An unknown template, an empty tree, a missing entry point. A workflow prints those first |

**The rules themselves, per template, are on
[What an upload is validated against](upload-validation.md)** - and the finding codes with them.

### When the request itself is wrong

| Situation | Status | `code` |
| --------- | ------ | ------ |
| A required parameter is missing | 400 | `MISSING_PARAMETER` |
| A parameter this endpoint does not accept | 400 | `UNKNOWN_PARAMETER` |
| A value that is not a slug, or a `type`/`source-format` that is not a known one | 400 | `INVALID_PARAMETER_VALUE` |
| A body that is not a readable list of paths - malformed JSON, no body, a `paths` that is not a list | 400 | `INVALID_PARAMETER_VALUE` |
| A body larger than a list of `max-paths` paths could be | 413 | `SIZE_LIMIT_EXCEEDED` - refused on its announced length **before the body is read**, and cut at the same limit **while it is read**, so a request that announces no length is bounded too |
| More paths than [`max-paths`](configuration.md#uploads) | 413 | `TOO_MANY_PATHS` - counted while the list is read, so the request is refused at the path after the cap |
| No token, or the role for another system | 401 / 403 | the security chain's own answer |

A rejection here is **not** counted as a rejected upload: `jeap.doc.upload.rejected` is about uploads, and a
validation never uploaded anything.

## Administering the documentation sites

Everything below `/api/sites` is operational: it asks for a site to be published, and it reports what the
documentation generator has been doing. The two are separate roles - `<system-name>_@sites_#admin` for the ask,
`<system-name>_@sites_#read` for the reading - and neither of them is the upload role, because a build
regenerates the documentation of every system on the site. See [Security](security.md).

A `{site}` this instance does not configure is answered with `404` on every one of them, and the detail names the
sites it does configure.

### Asking for a site to be published

```
POST /api/sites/{site}/builds
Authorization: Bearer ...
```

```json
{
  "site": "default",
  "partsRequested": 51,
  "picksUpWithinSeconds": 30
}
```

**There is no `force` parameter.** This endpoint forces by definition: what it asks for is not skipped by the
content digest, which is the whole reason it exists.

**A site is published as several builds, one per part** - see
[Generating the documentation](generation.md). This asks for every one of them, whether its content has moved
or not, which is what to use after changing the site template: the content of a part is then the same, so
nothing else would ask for a build. Nothing runs on the request; the builds are picked up within
`jeap.doc.build.poll-interval`, which is what `picksUpWithinSeconds` says.

`partsRequested` is how many parts were asked for - every part of the site, and `0` only for a site with no
parts at all.

### Asking for one part to be published

```
POST /api/sites/{site}/parts/{part}/builds
Authorization: Bearer ...
```

```json
{
  "site": "default",
  "part": "system-orders",
  "requested": true,
  "trigger": "MANUAL",
  "pendingSince": "2026-08-28T09:12:03Z",
  "picksUpWithinSeconds": 30
}
```

**Asking is not building.** The request is answered with `202` and the build happens afterwards: what the
endpoint leaves behind is the same collapsing request an upload and the import leave.

`requested` is `false` when a build of that part was already pending: the ask joined it, the answer is still
`202` because the build the caller wants is going to happen, and `pendingSince` and `trigger` then describe the
*earlier* request - which is the honest answer to when it will be built. Both are absent once an instance has
claimed the request, which means the build has already started. A part somebody asked for is built whether its
content moved or not.

### Reading the parts

```
GET /api/sites/{site}/parts
```

```json
[
  {
    "part": "shell",
    "documents": "the site itself",
    "routePrefixes": [],
    "environments": ["dev", "ref", "abn", "prod"],
    "publishedAt": "2026-09-04T05:06:11Z",
    "ageSeconds": 3600,
    "contentDigest": "6f1c…",
    "owedABuild": false
  },
  {
    "part": "system-orders",
    "documents": "the system orders",
    "routePrefixes": ["/dev/systems/orders/", "/systems/orders/"],
    "environments": ["dev", "ref", "abn", "prod"],
    "publishedAt": "2026-09-03T14:22:08Z",
    "ageSeconds": 60000,
    "contentDigest": "a12b…",
    "owedABuild": true
  }
]
```

What the site is published as: the shell, which carries the site's own pages and whatever no other part claims,
and one part per system. `routePrefixes` is what each of them answers for, and `contentDigest` is what its
content hashed to - a build of that part publishes nothing unless its own content hashes to something else.

**`ageSeconds` is the number to read.** A part nobody has rebuilt for a week either has not changed for a week
or has stopped being built, and the two are told apart by whether the other parts of the site are younger.

```
GET /api/sites/{site}/parts/{part}/builds?limit=20
```

is the build history of one part, in the shape [the builds](#reading-the-builds) answer with.

### Reading the state of the sites

```
GET /api/sites          # an array of the object below, one per configured site
GET /api/sites/{site}   # one of them
```

```json
{
  "site": "default",
  "title": "jEAP Documentation",
  "publishOnUpload": true,
  "environments": ["dev", "ref", "abn", "prod"],
  "pending": { "since": "2026-08-28T09:12:03Z", "trigger": "MANUAL" },
  "running": [],
  "published": { "id": 4710, "state": "SUCCEEDED", "finishedAt": "2026-08-28T03:06:11Z", "pageCount": 412 },
  "lastBuild": { "id": 4711, "state": "FAILED", "finishedAt": "2026-08-28T09:13:41Z" }
}
```

It answers *why is this site not updating* without a log search, which is what it is for: what the site is
configured to do is on it next to what has actually happened. There is no publication schedule any more - a
site is published when its architecture model is imported and when something is uploaded to it, and a site with
`publishOnUpload` false that no import feeds is behaving exactly as configured.

The three build objects on it - `published`, `running` and `lastBuild` - are the same record
[the build history](#reading-the-builds) answers with, trimmed above only to keep the example readable: they
carry the instance that ran the build, its object prefix and the reason it failed as well.

**`published` is the shell part's publication**, not the site's: a site is published as several builds and no
one of them is *the* published one. The shell answers for the site's own pages, so its build is what says the
site is being served at all - and a site whose shell publishes while its fifty-one system parts all fail reads
healthy here. [`/parts`](#reading-the-parts) is what says how the rest of it stands.

`lastBuild` is the newest build of any part, whatever became of it - and on a site nobody changes that is
normally a `SKIPPED` row, because a part whose content has not moved is not generated. So `published` and
`lastBuild` disagreeing is the ordinary case rather than a signal; what says builds are failing is the failure
alarm, and what says nothing is going through the parts at all is
`jeap.doc.build.last.check.age` - see [Observability](observability.md).

`pending` is `null` when nothing is owed and otherwise the **oldest** of the site's pending requests, which is
what says how long anything has been waiting. `running` is empty unless a build is happening right now; it is a
list because several parts build at once, and because an instance that lost its lock lease carries on building
until another one abandons it.

### Reading the builds

```
GET /api/sites/{site}/builds?limit=20
GET /api/sites/{site}/builds/{buildId}
```

The runs of the generator for that site - **every part of it** - newest first, in every state. `limit` defaults
to 20 and is brought into `1..100` rather than refused. Each build carries the `part` it produced, what it was
asked for by, what became of it, when it started and finished, how long it took, how much of that was
Docusaurus, the instance that ran it, what it published and how large that is, the `contentDigest` of what it
wrote - and `failureReason` when something went wrong.

A build in state `SKIPPED` published nothing and nothing is wrong with it: its part's content turned out to be
exactly what is already being served, so the site generator was never started. `contentDigest` is what that
comparison is made on: two builds of one part with the same digest wrote the same pages.

It carries **no memory number**. It used to: the kernel's high-water mark, reset around each build, which was
only that build's own while one build ran at a time. Several parts now build at once and each of them reset
what the others had accumulated, so the field said *exact* about a number that was not. What the container does
is `jeap.doc.container.memory.used` over whatever window is wanted - see
[Observability](observability.md#the-memory-of-the-container).

The build identifier comes from one sequence shared by every site, and a build is read by its site *and* its
identifier: the URL of one site never answers with a build of another.

## Asking for the architecture repository to be imported

Everything below `/api/architecture` is about the imports. It has its own root rather than a place under
`/api/sites` because **an import belongs to an environment and not to a site**: one doc service reads an
architecture repository per stage, and every site carrying that environment is generated from the same import.
The roles are the site ones - `<system-name>_@sites_#admin` to ask, `<system-name>_@sites_#read` to read -
because what an import is for is the documentation those sites publish.

**Why an operator needs this.** The model is imported on a schedule and a build reads what was stored, calling
the architecture repository not at all. So a correction made in the architecture repository is invisible to the
documentation until the next import - and forcing a publication does not help, because the build would publish
the same stored model again. Before this endpoint the only way to bring a correction forward was to restart an
instance and let the startup catch-up run.

```
POST /api/architecture/imports                             # every configured environment
POST /api/architecture/environments/{environment}/imports  # one of them
Authorization: Bearer ...
```

```json
{
  "environments": ["dev", "ref", "abn", "prod"],
  "alreadyAskedFor": [],
  "refused": [],
  "durable": false
}
```

Answered with `202`. **Nothing runs on the request**: the ask goes onto the same single-threaded executor the
schedule and the startup catch-up use, and the request thread is back within a millisecond. An import takes
minutes, and two at once would fetch two landscapes into a heap sized for one.

**The three lists say what became of each environment.** `environments` will be imported. `alreadyAskedFor`
were already on the queue and had not started reading, so the ask joined that import instead of putting the
same fetch behind it. `refused` were not queued at all - the queue is full, or the instance that answered is
stopping - and the answer is `429` where that is every environment asked for, because telling an operator
`202` for something that will not run is a lie about the one thing they are waiting for.

An environment whose import is **running** is in `environments`: it is imported once more when that run ends.
The run may already have read what the ask is about, which is exactly the case this endpoint exists for.

**`durable` is always `false`, and it is in the answer rather than left to be assumed.** Unlike a build
request, which is a row in the database, the import queue is the instance's own: an ask is lost if that
instance stops before it runs. Nothing is broken by that - the schedule imports the environment anyway at its
next occurrence - but it is the reason an ask is not a promise.

An `{environment}` this instance reads no architecture repository for is answered with `404`, and an instance
that reads none at all answers `404` on both. The typo is the likely reason for asking twice, and importing an
environment nobody reads would take a lock and log a failure instead of saying so.

### Reading what the imports have been doing

```
GET /api/architecture/environments
```

```json
[
  {
    "environment": "prod",
    "sourceUrl": "https://archrepo.example.ch/archrepo",
    "imports": [
      {
        "kind": "MODEL",
        "itemCount": 52,
        "complete": true,
        "lastAttemptAt": "2026-09-07T18:45:26Z",
        "lastSuccessAt": "2026-09-07T18:45:31Z",
        "lastOutcome": "IMPORTED",
        "failureReason": null
      }
    ]
  }
]
```

One entry per configured environment, and within it one per kind - the model first, because it decides which
systems and components exist and therefore which artifacts are orphans. `lastOutcome` is not derivable from the
two timestamps: a run that stopped at its deadline stored what it had reached and is neither a success nor a
failure. `complete` is why the next run does or does not trust the index tag it was given - see
[Importing the architecture model](architecture-import.md).

## Everything outside /api is the documentation

The doc service is a web server as well as an API: every path that is not the API, the actuator, the OpenAPI
description or the Swagger UI serves the generated documentation site, to anyone who can reach the service. See
[Generating the documentation](generation.md).

One of those paths is not published output but an answer of the service: `live-status.json`, **at the base URL
of a site and only there**, says when each of the site's environments last read its architecture repository,
whether the import is behind, and when the import fires next. A file of that name deeper in the tree is content
like any other. It is answered before the site has to be published at all, so a site that has never been
generated still reports why, and it is never cached. It belongs to the site and is open exactly as the site is -
the page describing the documentation fetches it, because those statements would freeze into a page that is not
rebuilt. What it may carry is the same decision as for that page; the administration API above is where a
failure reason and the upstream URL stay.

## Related

- [Uploads](uploads.md)
- [Architecture](architecture.md)
- [Security](security.md)
