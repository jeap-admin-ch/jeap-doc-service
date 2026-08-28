# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-08-28

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.4.0 → 40.5.0 (minor)
- **react-dom**: 19.2.0 → 19.2.8 (patch)
- **react**: 19.2.0 → 19.2.8 (patch)
- **minimatch**: 9.0.9 → 10.2.6 (major)

## [0.5.0] - 2026-08-28

### Added

- **The doc service generates and serves the documentation site.** It runs the site generator over a template it
  ships, publishes the result to the object storage and serves it - one tree per environment, with an
  environment switcher, offline search, `llms.txt` and diagrams rendered from their source in the browser.
- **Documentation sites are configured** under `jeap.doc.sites`; an instance that configures none gets the
  `default` site. A build is asked for by an upload and by a schedule, runs once per site across all instances,
  and is recorded in `documentation_build` - the newest successful row *is* the published site.
- **A site administration API** under `/api/sites`: `POST /api/sites/{site}/builds` asks for a site to be
  published, and the `GET` endpoints answer what each site is configured to do, what is pending, what is running
  and what has been built. Asking is not building - the ask leaves the same collapsing request an upload leaves,
  recorded as the new `MANUAL` build trigger. Two new roles: `<system-name>_@sites_#admin` for the ask and
  `<system-name>_@sites_#read` for the reading.
- **The site search is scoped to the environment being read.** The index is split one part per environment and
  the search box takes the part from the page it is on, so a query answers with the tree the reader is in
  instead of with the same page once per environment. The search page carries a selector for changing it.
- **Meters for the uploads and the builds** under `jeap.doc.*`, with the alarms in `docs/observability.md`.
- New pages: [Generation](docs/generation.md), [The site image](docs/site-image.md),
  [Observability](docs/observability.md), [Operating the bucket](docs/operating-the-bucket.md).

### Changed

- **An instance that is stopped gives up its build instead of being cut off**: the generator is destroyed, the
  build is recorded as `ABORTED` rather than `FAILED`, its lock is given back and it is asked for again. A build
  left behind by an instance that was *killed* is picked up as `RECOVERY` - once, so it cannot become a crash
  loop. In-flight requests are finished on shutdown (`server.shutdown=graceful`).
- **Everything outside `/api` is now the documentation site**, served to anyone who can reach the service, and
  the Content Security Policy allows what the site generator emits (`'unsafe-inline'`, `'wasm-unsafe-eval'`,
  `worker-src 'self' blob:`). An instance that overrode the policy has to follow.
- **The domain no longer depends on a metrics library, on Jackson or on a JSON format.** The meters moved into a
  new `jeap-doc-metrics` adapter; writing what the site template reads moved into `jeap-doc-sitegenerator`. An
  instance that lists the modules by hand has to add `jeap-doc-metrics`.

### Fixed

- **An upload naming a site the instance does not configure is rejected** rather than stored and published
  nowhere: `400 UNKNOWN_SITE`, naming the sites that do exist.

### Security

- **The site template's transitive npm dependencies are pinned past their advisories** through `overrides` in
  `package.json`. `image-size` has no fixed release at all - why that is not exploitable here is written down in
  `.trivyignore`.

### Requires

- **A stop timeout above the shutdown budget on the platform**: `stopTimeout: 90` on ECS,
  `terminationGracePeriodSeconds: 90` on Kubernetes. The ECS default of 30 seconds turns the shutdown into a
  kill. See [Generation](docs/generation.md).
- **Node and the site template's dependencies in the image.** The service does not start without them, nor when
  they were installed from a different lockfile than the one it carries. See
  [The site image](docs/site-image.md).
- **Node 24, Docker and Chrome to build this repository** - the site is really generated and really driven in a
  browser by the tests.

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.2.0 → 40.4.0 (minor)

## [0.4.0] - 2026-08-26

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.1.0 → 40.2.0 (minor)

## [0.3.0] - 2026-08-25

### Added

- Uploaded documentation is recorded in PostgreSQL and its bundle stored on S3, left `PENDING` for the
  documentation generator; a nightly job removes uploads older than 14 days, the bundles by a lifecycle rule.
- `GET /api/uploads/docs/{uploadId}` answers what became of an upload of the own system.

### Changed

- **The upload moved to `PUT /api/uploads/docs/{uploadId}`**, answers `201` when it stored a bundle, and is
  idempotent in its upload id - what a retry does is described in [Uploads](docs/uploads.md).
- **`Content-Length` is mandatory** (`411` without it), a bundle may be 50MB by default, and an upload is
  authorized against the `uploads` resource: `<system-name>_%<system>_@uploads_#write`.

## [0.2.0] - 2026-08-24

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.0.0 → 40.1.0 (minor)

## [0.1.0] - 2026-08-21

### Added

- Initial version of the jEAP Doc Service: ports-and-adapters module structure, REST API with OpenAPI and an
  endpoint receiving a documentation set, semantic role authorization restricting a system to its own
  documentation, S3 object storage with a startup check of the bucket, and the PostgreSQL connection with Flyway.
