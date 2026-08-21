# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- The upload endpoint takes the full description of a documentation set: what it documents (`type`, `system`,
  `component`/`library`, `template`), the format of its documents (`source-format`, and `location`, `topic` and
  `label` for HTML) and their provenance (`source-repository`, `source-revision`, `source-ref`,
  `source-timestamp`, `version`, `build-url`, `generated-at`). Rejected uploads answer with an RFC 9457 problem
  document naming the reason. An upload may name the `site` its documents belong to; without it they belong to
  the default site.
- The upload endpoint is served at `/api/uploads/{uploadId}` and rejects a bundle larger than
  `jeap.doc.upload.max-size` (100MB by default) with `413`, without reading it into memory.

## [0.1.0] - 2026-08-21

### Added

- Initial version of the jEAP Doc Service: ports-and-adapters module structure, REST API with OpenAPI and an
  endpoint receiving a documentation set, semantic role authorization restricting a system to its own
  documentation, S3 object storage with a startup check of the bucket, and the PostgreSQL connection with Flyway.
