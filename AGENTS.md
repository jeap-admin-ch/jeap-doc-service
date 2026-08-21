# AGENTS.md

This file provides guidance to coding agents when working with code in this repository.

## Project Overview

The jEAP Doc Service receives the documentation of systems, components and libraries from build pipelines, stores
it on S3, generates the documentation site from it together with the architecture model of the jEAP Arch Repo
Service, and serves that site as a web server. Downstream projects create their own instances by depending on
`jeap-doc-service-instance` and adding configuration.

Built on Java 25 and `jeap-spring-boot-parent` (Spring Boot 4).

The service currently receives documentation sets over its REST API; the documentation model, the storage of the
uploaded documentation and the generator are built on top of it.

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
- `jeap-doc-web/` - `DocServiceApplication`, `UploadController` and `DocsWebSecurityConfiguration`.
- `jeap-doc-service-instance/` - POM-only module for downstream instances.

Keep the layering: business logic goes into the domain, technology into an adapter, and an adapter never depends
on another adapter.

## Conventions worth knowing

- **Upload parameters**: the query parameters of the upload endpoint are kebab-case and mirror the keys of the
  doc workflow configuration (`source-format`, `source-repository`, ...). Which of them are required depends on
  the type and the source format - the rules live in `DocumentationSetUpload`, an unknown parameter is rejected.
- **Security**: semantic roles, with the system a role is granted for in the tenant part
  (`hasRole(#system, 'docs', 'write')`) - see `docs/security.md`.
- **Swagger**: contributed by `jeap-spring-boot-swagger-starter`, disabled unless `jeap.swagger.status` is set;
  the description is served at `/api-docs`, the UI at `/swagger-ui.html`.
- **Startup validation**: configuration errors of an instance should fail the startup instead of the first
  request - the bucket check is the example to follow.
- **Tests**: unit tests for the domain, Testcontainers integration tests (`*IT`) against real PostgreSQL and
  RustFS for the adapters and the web layer. `DocServiceIntegrationTestBase` starts both containers once per JVM
  and disables the permit-all chain of the jEAP security test starter, so the tests see production security.

## Documentation

`README.md` stays short and links into `docs/`, which is published to
[jeap-admin-ch.github.io](https://jeap-admin-ch.github.io). Pages are written in English and must be valid MDX -
the build fails otherwise.
