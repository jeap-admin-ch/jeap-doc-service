# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
