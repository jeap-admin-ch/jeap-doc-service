# Configuration

The doc service's own properties live under the `jeap.doc` prefix. Everything else comes from the jEAP starters
it builds on - see [jeap-spring-boot-starters](https://jeap-admin-ch.github.io/docs/jeap-spring-boot-starters/)
for the security, object storage, database, Swagger and web header properties.

## Object storage

```yaml
jeap:
  doc:
    storage:
      bucket: my-doc-service-documents
```

| Property                  | Default | Description                      |
| ------------------------- | ------- | -------------------------------- |
| `jeap.doc.storage.bucket` | -       | Bucket holding the documentation |

The bucket is checked while the service starts: **the service does not start** when the property is missing or
when the bucket cannot be reached with the configured credentials. A missing bucket is a configuration error of
the instance, and it should surface in the deployment instead of in the first upload.

The connection to the object storage itself is configured with the `jeap.s3.client.*` properties of the jEAP
object storage starter.

## Uploads

| Property                   | Default | Description                                                             |
| -------------------------- | ------- | ----------------------------------------------------------------------- |
| `jeap.doc.upload.max-size` | `100MB` | Maximum size of an uploaded bundle; a larger one is rejected with `413` |

The service stops reading a bundle as soon as it exceeds the limit, so an oversized upload cannot fill its heap.

## Database

The doc service persists on PostgreSQL with Flyway. The instance configures the connection, and it also chooses
how the connection is authenticated: on AWS by adding `jeap-spring-boot-postgresql-aws-starter`, elsewhere with a
user and a password.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/docservice
    username: docservice
    password: ${DB_PASSWORD}
```

The migrations of the doc service live in `jeap-doc-persistence` under `db/migration` and are applied while the
service starts.

## API documentation

The jEAP Swagger starter disables the OpenAPI endpoints by default. An instance switches them on where it wants
them:

```yaml
jeap:
  swagger:
    status: OPEN      # or SECURED, with jeap.swagger.secured.username/password
```

## Defaults the service sets itself

The service ships `jeapDocDefaultProperties.properties`, which an instance can override:

| Property                                      | Value                                     | Why                                                                                 |
| --------------------------------------------- | ----------------------------------------- | ----------------------------------------------------------------------------------- |
| `jeap.web.headers.content-security-policy`    | a policy allowing only `self` and `data:` | The documentation is self-contained and loads no external content                   |
| `jeap.web.headers.additional-content-sources` | empty                                     | The web config starter would otherwise add the OAuth2 issuer's origin to the policy |
| `spring.jpa.open-in-view`                     | `false`                                   | jEAP guideline                                                                      |

## Related

- [Getting started](getting-started.md)
- [Security](security.md)
