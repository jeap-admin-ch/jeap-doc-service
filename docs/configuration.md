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
      upload-prefix: uploads
      spool-directory: /var/doc-service/spool
```

| Property                           | Default              | Description                                                       |
| ---------------------------------- | -------------------- | ------------------------------------------------------------------ |
| `jeap.doc.storage.bucket`          | -                    | Bucket holding the documentation                                  |
| `jeap.doc.storage.upload-prefix`   | `uploads`            | Prefix the bundles of the uploads are stored under, in the bucket |
| `jeap.doc.storage.spool-directory` | JVM temp directory   | Directory an uploaded bundle is spooled to while it is transferred |

The uploaded documentation lies under its own prefix, separately from the documentation the generator writes: an
upload is stored as `<upload-prefix>/docs/<id>/<attempt>/bundle.zip`, where `<id>` is the identifier the doc
service gave the upload. Every attempt of an upload writes its own object - see
[Uploads](uploads.md#how-an-upload-is-cleaned-up-again) for why - and the upload records the one it points at.

An uploaded bundle is written to a file before it is transferred to the object storage, which is what keeps it
out of the memory of the service. **The spool directory should therefore be on a disk**: a `/tmp` that is a
memory-backed tmpfs - as containers with a read-only root filesystem often have - would put the bundle back into
memory. It needs room for as many bundles of `jeap.doc.upload.max-size` as are uploaded at the same time, and the
service **does not start** when it cannot write there.

The bucket is checked while the service starts: **the service does not start** when the property is missing or
when the bucket cannot be reached with the configured credentials. A missing bucket is a configuration error of
the instance, and it should surface in the deployment instead of in the first upload.

The connection to the object storage itself is configured with the `jeap.s3.client.*` properties of the jEAP
object storage starter.

## Uploads

| Property                              | Default | Description                                                                          |
| ------------------------------------- | ------- | -------------------------------------------------------------------------------------- |
| `jeap.doc.upload.max-size`            | `50MB`  | Maximum size of an uploaded bundle; a larger one is rejected with `413`               |
| `jeap.doc.upload.in-progress-timeout` | `PT2M`  | How long an upload may be in progress before another attempt under the same upload id takes it over |

The service stops reading a bundle as soon as it exceeds the limit, so an oversized upload cannot fill its heap.

An upload that is rejected before its body is read - an unannounced size, a wrong parameter, another attempt of
the same upload already running - can only be answered while the rest of the request is discarded, which the
servlet container does up to `server.tomcat.max-swallow-size`. **The service derives that limit from `max-size`,
plus a margin**, so it follows whatever an instance accepts and there is nothing to keep in step; below the size
of an accepted bundle a rejected upload would see a closed connection instead of the problem document telling it
what to fix. The margin is there for the one rejection that is by definition larger than the limit - a bundle
that is too large - and an upload overshooting it by more than the margin still ends in a closed connection.

The in-progress timeout is what frees an upload id whose service died while the bundle was streaming - see
[Uploads](uploads.md#idempotency-what-a-retry-does). It has to be longer than a legitimate upload of `max-size`
takes, so an instance that raises the maximum size raises the timeout with it.

## Housekeeping

```yaml
jeap:
  doc:
    upload:
      housekeeping:
        enabled: true
        retention: P14D
        cron: "0 30 2 * * *"
```

| Property                                   | Default         | Description                                                   |
| ------------------------------------------ | --------------- | --------------------------------------------------------------- |
| `jeap.doc.upload.housekeeping.enabled`     | `true`          | Whether old uploads are removed at all                        |
| `jeap.doc.upload.housekeeping.retention`   | `P14D`          | How long an upload is kept after it was last received         |
| `jeap.doc.upload.housekeeping.cron`        | `0 30 2 * * *`  | When to look, in the time zone of the service                 |

The job removes the uploads **from the database only**, whatever state they are in; the bundles are expired by a
lifecycle rule of the bucket, which has to be set a little longer than the retention - see
[Uploads](uploads.md#how-an-upload-is-cleaned-up-again). Of several instances only one runs it, using a lock in
the `shedlock` table.

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
- [Uploads](uploads.md)
- [Security](security.md)
