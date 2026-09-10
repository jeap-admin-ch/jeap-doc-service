# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.1.0] - 2026-09-10

- Reactions are imported from the reaction observer service , as steps of the architecture import.
- Runtime views for reactions are generated: Chapter 6 of a system and of a component, and a message's page.

## [2.0.0] - 2026-09-09

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.9.2 → 40.11.0 (minor)
- **react-dom**: 19.2.8 → 19.3.0 (minor)
- **react**: 19.2.8 → 19.3.0 (minor)
- **js-yaml**: 4.3.2 → 5.4.1 (major)

## [1.5.0] - 2026-09-09

- The site has a search again: a box in the navbar and a results page at `/search`, both scoped to the reader's environment. A result names the system and component it is in, marks what matched, and leads to the page's route rather than to its numbered path.
- One index over the whole site, built at the end of the build pass that published it - no schedule, and never able to fail a publication.
- `jeap.doc.build.history-retention` is `P14D`, down from `P90D`. **pagefind** joins the site template, so an instance has to rebuild its site image or set `jeap.doc.search.enabled: false`.
- Getting started documents `jeap-doc-service-instance` as the **parent** of an instance beside the dependency form it already showed: the parent version then names the doc service, the dependency management of the template and the jEAP parent that version was built against, and it says which two settings of this build an instance undoes - the javadoc artifact, and the skip an `unpack-site-manifest` execution inherits.

## [1.4.0] - 2026-09-08

- Component documentation is generated: an arc42 tree per component - context view, database schema, REST API, messages.
- A site is published as one build per system, several at a time, and only where the content digest moved.
- A component context view names the components it exchanges something with, each inside the system that owns it.
- A component's *Messages* page carries the messages of **every** system it has a contract on, not only its own system's, names the defining system in a column of its own and links into that system's tree. A contract is matched on the system and the component, so a same-named component of another system is not mistaken for it.
- An upload's structure can be checked before it is sent: `POST /api/uploads/docs/validation`. A leading number is taken off a document's name before it is compared, because the site generator does the same: `01-rest-api.md` is reported as a reserved name, and two names that differ only in their number as `COLLIDING_NAME`.
- The architecture import asks for the documentation **once, after its whole chain** rather than after the model step. A build is no longer started while the OpenAPI specifications and database schemas of the same environment are still being fetched, and an artifact that is newly replicated while the landscape stands still is published rather than waiting for the model to move.
- After the upgrade to a site published in parts, the previous whole-site publication goes on serving its stylesheets, scripts and images too: a shared path that the site's shared prefix does not hold falls back to the publication of the part that owns it. Without it every page of the site arrived without its layout until the shell had been rebuilt.
- `DELETE /api/sites/{site}/parts/{part}` answers `503` and **keeps the build records** when the objects could not be deleted, when a build holds the part, or when it was published again in between. The records are the only thing that names what a part published, so removing them after a failed deletion left the objects in the bucket with nothing to find them by.

### Removed

- **The About page no longer shows a page count or a size.** Every part writes those numbers, and only the part carrying that page publishes them where the page can fetch them - so both read as the whole site's while being one part's.

### Dependencies
- **@matfsw/docusaurus-plantuml-plugin**: 1.7.1 → 1.8.1 (minor), with **@plantuml/core** 1.2026.7 → 1.2026.8. An instance has to rebuild its site image: the startup check refuses a `node_modules` installed from a different lockfile.
- **js-yaml**: 4.3.1 → 4.3.2 and **qs**: 6.15.3 → 6.16.0, both pinned through `overrides` because both are transitive. They close CVE-2026-84375 (`js-yaml`, denial of service in YAML parsing) and CVE-2026-82417 and CVE-2026-82562 (`qs`, denial of service in `stringify` and through an array-limit bypass). The site image has to be rebuilt for the same reason as above.

## [1.3.0] - 2026-09-06

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.8.0 → 40.9.2 (minor)

## [1.2.0] - 2026-09-03

### Added

- **The system documentation is generated** as one arc42 tree per system: components, messages, schemas, diagrams.
- **The architecture repository is imported hourly into the doc service's own database**; a build reads that copy.
- **arc42 is a module of its own**, `jeap-doc-template-arc42`, and every page goes through `jeap-doc-markdown`.
- **An "About This Documentation" page** per environment names what the model contributed and what the build cost.
- **Every site but the default is served below `/site/`**: the URL of every named site changes, with no redirect.

## [1.1.1] - 2026-09-03

### Dependencies
- **@matfsw/docusaurus-plantuml-plugin**: 1.7.0 → 1.7.1 (patch)

## [1.1.0] - 2026-09-02

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.5.0 → 40.7.0 (minor)
- **serialize-javascript**: 7.1.0 → 7.1.1 (patch)
- **docusaurus-plugin-llms**: 0.5.1 → 0.6.0 (minor)
- **@matfsw/docusaurus-plantuml-plugin**: 1.6.2 → 1.7.0 (minor)

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
