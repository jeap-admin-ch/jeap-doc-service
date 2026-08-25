# AGENTS.md

This file provides guidance to coding agents when working with code in this repository.

## Project Overview

The jEAP Doc Service receives the documentation of systems, components and libraries from build pipelines, stores
it on S3, generates the documentation site from it together with the architecture model of the jEAP Arch Repo
Service, and serves that site as a web server. Downstream projects create their own instances by depending on
`jeap-doc-service-instance` and adding configuration.

Built on Java 25 and `jeap-spring-boot-parent` (Spring Boot 4).

The service receives documentation sets over its REST API, records them in PostgreSQL and stores their bundles on
S3, where the documentation generator will pick them up.

## Build Commands

```bash
# Build everything, including the Testcontainers tests (needs a running Docker daemon)
./mvnw verify

# Build without tests
./mvnw install -Dmaven.test.skip=true

# One module
./mvnw install -pl jeap-doc-web

# One test class or method
./mvnw verify -pl jeap-doc-web -Dit.test=UploadApiIT
./mvnw verify -pl jeap-doc-web -Dit.test=UploadApiIT#upload_whenWriteRoleForAnotherSystem_thenForbidden

# Regenerate the third-party license list after a dependency change
./mvnw org.codehaus.mojo:license-maven-plugin:aggregate-add-third-party
```

## Architecture

Ports and adapters, one auto-configuration per module - see `docs/architecture.md`:

- `jeap-doc-domain/` - the domain: model, services and the ports it needs (`…doc.domain.port`). Depends on no
  adapter, no web framework and no driver.
- `jeap-doc-persistence/` - JPA adapter on PostgreSQL, and the Flyway migrations in `db/migration`.
- `jeap-doc-objectstorage/` - S3 adapter over the `S3Client` of `jeap-spring-boot-object-storage-starter`, plus
  `DocStorageBucketAvailabilityCheck`, which fails the startup when the configured bucket is not available.
- `jeap-doc-web/` - `DocServiceApplication` and `DocsWebSecurityConfiguration`; the REST API lives under
  `web/api`, with one subpackage per endpoint family (`web/api/upload`) whose classes are package-private. A
  family that serves more than one kind of resource has one subpackage per kind (`web/api/upload/docs`), and the
  few classes the kinds share stay public in the family's package (`UploadPaths`, `UploadBodies`,
  `UploadParameterInterceptor`).
- `jeap-doc-service-instance/` - POM-only module for downstream instances.

Keep the layering: business logic goes into the domain, technology into an adapter, and an adapter never depends
on another adapter.

## Conventions worth knowing

- **Upload paths**: everything below `/api/uploads` is an upload, and the segment after it names the kind of
  thing uploaded - `/api/uploads/docs/{uploadId}` for documentation. A new kind gets a new segment, a new
  subpackage and its own parameters; it never gets a parameter on an existing endpoint. The paths are the
  constants of `UploadPaths`.
- **Upload parameters**: the query parameters of the upload endpoint are kebab-case and mirror the keys of the
  doc workflow configuration (`source-format`, `source-repository`, ...). Which of them are required depends on
  the type and the source format - the rules live in `DocumentationUploadDescriptor` in the domain, and
  `DocumentationUploadDto` only binds the request and converts it. An unknown parameter is rejected
  by `UploadParameterInterceptor`, which runs before the parameters are bound so a typo is reported as such; the
  parameters it knows are the ones the kind of upload registers it with.
- **API objects**: a type that crosses the API boundary is named after the domain type it represents, with a
  `Dto` suffix (`DocumentationUploadDto`, `DocumentationTypeDto`, `SourceFormatDto`) - spelled `Dto`, as
  everywhere else in jEAP. Controllers, interceptors, configurations and exceptions are components, not
  representations, and keep their names.
- **Security**: semantic roles, with the system a role is granted for in the tenant part
  (`hasRole(#system, 'uploads', 'write')`) - see `docs/security.md`.
- **Swagger**: contributed by `jeap-spring-boot-swagger-starter`, disabled unless `jeap.swagger.status` is set;
  the description is served at `/api-docs`, the UI at `/swagger-ui.html`.
- **Startup validation**: configuration errors of an instance should fail the startup instead of the first
  request - the bucket check is the example to follow.
- **What is recorded of a bundle**: the storage port reports where it put a bundle *and* the SHA-256 of what it
  wrote (`StoredBundle`), computed while the bundle is spooled - the bytes are read once anyway. The digest is
  the service's own: no client sends one, and it is not passed to the object storage.
- **Uploads are cleaned up from two sides**: the doc service removes the rows (`DocumentationUploadHousekeeping`,
  nightly, `jeap.doc.upload.housekeeping.retention`), the bucket expires the bundles by a lifecycle rule on the
  tag `jeap-doc-content=upload`. The rule has to outlive the retention, or an upload would point at a bundle that
  is gone. Scheduled jobs take a ShedLock lock so only one instance runs them.
- **An object belongs to one attempt**: the key is `<prefix>/docs/<id>/<attempt>/bundle.zip`, and the upload
  records the key it got back. Two attempts of one upload can be writing at the same time - a straggler must not
  be able to replace what the attempt that took the upload over stored.
- **Idempotency**: the upload id of the path is the idempotency key - what a repetition of an upload does is
  decided in `DocumentationUploadService` and described in `docs/uploads.md`. An upload is recorded before its
  bundle is read, and no transaction is open while the bundle streams; `UploadTransactionBoundaryIT` pins both.
- **Tests**: unit tests for the domain, Testcontainers integration tests (`*IT`) at two levels. Each adapter is
  tested on its own container - `PostgresTestContainerBase` in `jeap-doc-persistence`, `RustFsTestContainerBase`
  in `jeap-doc-objectstorage` - so a failing constraint or object key names the adapter. The end-to-end tests live
  in `jeap-doc-web` on `DocServiceIntegrationTestBase`, which starts both containers once per JVM and disables the
  permit-all chain of the jEAP security test starter, so they see production security.

## Logging

**Every upload has to be findable in the log by the id a pipeline quotes.** A team reporting a failed upload knows
its `uploadId`; the doc service knows the identifier its bundle is stored under. One line ties them together, and
every further line about that upload names both:

```
Receiving the upload 8f1c9a2e-… (42), attempt 1: COMPONENT_DOCS of the system wvs (foo-bar-scs) on the site default, 184320 bytes.
Stored the upload 8f1c9a2e-… (42) of the system wvs as uploads/docs/42/1/bundle.zip (184320 bytes, sha-256 9f86d0…), pending generation.
```

- **One line per outcome, one place per line.** A rejected upload is logged by `UploadExceptionHandler`, which
  sees every rejection - the ones raised before the body is read as well as the ones raised while it streams;
  the domain only records the state. Do not log the same event twice on its way out.
- Name the upload id first, then the identifier of the upload, then the system. An upload that has no identifier
  yet - rejected before it was recorded - is logged with what the request carried.

**The level says who has to act, not how bad it feels:**

| Level | For | Examples |
| ----- | --- | -------- |
| `ERROR` | **Only what the operators of the doc service have to react to and be alarmed on**: infrastructure that is not doing its job, and bugs | The object storage refused a bundle, the database is unreachable, an unexpected exception |
| `WARN` | Something a **caller** got wrong, and situations worth noticing that need no action | Every rejected upload (missing, unknown or invalid parameter, no `Content-Length`, a bundle that is too large or shorter than announced, an upload id used for a different upload), an attempt taken over as abandoned, a temporary file that could not be deleted |
| `INFO` | The **course of business**: what the service did | An upload received, stored, or repeated; a system, component or library documented for the first time; the startup checks |
| `DEBUG` | Detail for looking closer | Object keys, an answer whose cause is already logged where it happened |

**A caller's mistake never reaches `ERROR`.** It is nothing the operators can act on, and an alarm on it is a
false alarm - the team whose pipeline sent it has to find it, which is what `WARN` with the upload id is for. The
one thing that looks like a caller error but is not is an upload refused because another attempt of it is
running: retrying is what a pipeline is supposed to do, so that stays at `INFO`.
`UploadExceptionHandlerTest` pins the level of every problem code; add the new code there when you add one.

## Traps this service has already fallen into

Every rule below cost a review finding. They are cheap to follow and expensive to rediscover.

- **Catch the specific exception before the general one.** Wrapping "anything that goes wrong here" into one
  service-level error also swallows the errors that already carry a meaning: a bundle that is shorter than its
  `Content-Length` was reported as `STORAGE_FAILED` because the storage call was wrapped in
  `catch (RuntimeException)`. When a `catch` exists to *record* a failure, re-throw what the caller has to hear
  and wrap only the rest - and log a caller's mistake at info, not at error.
- **A write that follows a claim has to be conditional on still owning the claim.** An attempt that is taken over
  as abandoned keeps running: it will finish and write its outcome. Every update after a claim therefore carries
  `where ... and attempt = :attempt` (or a `@Version`), and a write that hits no row is a lost claim, not an
  error.
- **Know what `MockMvc` cannot see.** It never opens a socket, so anything the servlet container decides is
  invisible to it: `Content-Length` (it derives it from the content, and cannot omit or fake it),
  `Transfer-Encoding`, connection resets, and how much of a rejected request is swallowed
  (`server.tomcat.max-swallow-size` - which has to be at least `jeap.doc.upload.max-size`, or a rejected upload
  never sees its problem document). When a rule lives at that level, cover it by configuration and say in the test
  what is *not* covered.
- **The servlet container answers before the service does.** A body that stops before its announced length never
  reaches the length check: Tomcat raises a broken connection while it is being read, and answers `400` itself.
  The service's own reason has to be derived from what it managed to read (`S3DocumentationBundleStorage`), and
  the documentation says which answers come from the container rather than from the API. How much of a rejected request the container
  swallows before answering is derived from `jeap.doc.upload.max-size` by
  `UploadSwallowSizeEnvironmentPostProcessor` - a limit that has to follow another one is computed, not written
  into a property file for instances to keep in step.
- **Assert a problem code where the client sees it.** Every code in `docs/api.md` needs one test through the
  endpoint. An assertion in the adapter that raises the code proves the adapter, not the API: that is how an
  unreachable `400` survived.
- **The web layer talks to domain services, not to driven ports.** A controller that injects a repository port
  ends up implementing domain rules - "a system only sees its own uploads" belongs in the service, where it is
  testable without a web context.
- **Keep one definition of a thing.** A domain factory that only tests call is a second definition waiting to
  drift; let the adapter build its row from it.
- **Every change to code or configuration ends with a look at the documentation.** `docs/`, `AGENTS.md` and
  `CHANGELOG.md` are part of the change, not homework for later. Grepping for what you renamed - a class, a
  property, a status code - finds the easy half. The other half has no identifier to grep for: a sentence that
  promised something the service no longer does. So after changing *what the service guarantees*, re-read the
  pages that describe it, not only the ones that name it.

## Commits

`JEAP-1234 <message>` - every commit message starts with the Jira ID of the story it belongs to and stays short
and to the point. Ask for the Jira ID when it is not known; do not commit without one.

## Documentation

`README.md` stays short and links into `docs/`, which is published to
[jeap-admin-ch.github.io](https://jeap-admin-ch.github.io). Pages are written in English and must be valid MDX -
the build fails otherwise.

**A page is only true until the next commit.** Before finishing a change, go through the pages that describe what
it touched and check them against what the code now does:

| Changed | Read |
| ------- | ---- |
| Anything a caller sees - a path, a parameter, a status, a problem code, a response | `docs/api.md` |
| What happens to an upload - its states, retries, storage, clean-up, what is guaranteed of it | `docs/uploads.md` |
| A property, a default, or something an instance has to provision | `docs/configuration.md` |
| A module, a port, or the way the parts fit together | `docs/architecture.md` |
| A role, or who may do what | `docs/security.md` |

Three statements in `docs/uploads.md` went stale within a single branch this way - each true when it was written,
and false one commit later, without any name changing that a search would have found.
