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

| Property                                   | Default            | Description                                                                                                                                                                                                          |
|--------------------------------------------|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.doc.storage.bucket`                  | -                  | Bucket holding the documentation                                                                                                                                                                                     |
| `jeap.doc.storage.upload-prefix`           | `uploads`          | Prefix the bundles of the uploads are stored under, in the bucket                                                                                                                                                    |
| `jeap.doc.storage.site-prefix`             | `sites`            | Prefix the generated sites are published under, in the bucket                                                                                                                                                        |
| `jeap.doc.storage.spool-directory`         | JVM temp directory | Directory an uploaded bundle is spooled to while it is transferred                                                                                                                                                   |
| `jeap.doc.storage.publication-concurrency` | `16`               | How many files of a generated site are written into the bucket at a time. A site is thousands of small files, so publishing it is bound by round trips; above the connection pool of the S3 client this buys nothing |

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

| Property                              | Default | Description                                                                                         |
|---------------------------------------|---------|-----------------------------------------------------------------------------------------------------|
| `jeap.doc.upload.max-size`            | `50MB`  | Maximum size of an uploaded bundle; a larger one is rejected with `413`                             |
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

| Property                                 | Default        | Description                                           |
|------------------------------------------|----------------|-------------------------------------------------------|
| `jeap.doc.upload.housekeeping.enabled`   | `true`         | Whether old uploads are removed at all                |
| `jeap.doc.upload.housekeeping.retention` | `P14D`         | How long an upload is kept after it was last received |
| `jeap.doc.upload.housekeeping.cron`      | `0 30 2 * * *` | When to look, in the time zone of the service         |

The job removes the uploads **from the database only**, whatever state they are in; the bundles are expired by a
lifecycle rule of the bucket, which has to be set a little longer than the retention - see
[Uploads](uploads.md#how-an-upload-is-cleaned-up-again). Of several instances only one runs it, using a lock in
the `shedlock` table.

## Documentation sites

**Which sites exist is configuration, not something the service works out from what has been uploaded.** An
upload naming a site nobody configured is rejected: a typo in a doc workflow would otherwise produce a second
documentation site, generated and served next to the real one, and nobody would notice.

An instance that configures no site gets one called `default`, with every default value below - which is all a
single-site instance needs. **An instance that configures named sites has a `default` site only if it names
one**, and an upload that omits the `site` parameter targets `default` whatever else is configured - so an
instance with named sites that expects such uploads has to configure `default` as well. Written out in full, that default site is:

```yaml
jeap:
  doc:
    sites:
      default:
        title: Documentation                   # the site id for any site but this one
        tagline:                               # none
        logo:                                  # the template's own mark
        favicon:                               # the logo
        color-scheme: jeap
        publication-schedule: "0 5 6-20 * * *"
        publish-on-upload: true
        environments:
          - id: dev
            short-name: DEV
            label: Development
            order: 1
            main: false
            latest: true                       # documentation of what is not deployed anywhere yet
          - id: ref
            short-name: REF
            label: Reference
            order: 2
            main: false
            latest: false
          - id: abn
            short-name: ABN
            label: Acceptance
            order: 3
            main: false
            latest: false
          - id: prod
            short-name: PROD
            label: Production
            order: 4
            main: true                         # served at the site root, and the only indexed tree
            latest: false
```

Every one of those values is what an instance gets without writing any of them down. A second site, and a first
one that departs from the defaults:

```yaml
jeap:
  doc:
    sites:
      default:
        title: JME Documentation
        publication-schedule: "0 5 6-20 * * *"
      governance:
        title: Governance
        tagline: How the platform is governed
        color-scheme: neutral
        logo: classpath:/branding/governance.svg
        publication-schedule: "0 15 * * * *"   # refreshed hourly
        publish-on-upload: false               # on the schedule only
        environments:                          # this one exists on production only
          - id: prod
            short-name: PROD
            label: Production
            order: 1
            main: true
            latest: true
```

| Property                      | Default                                                         | Description                                                                                                                                                                                                                                                                                                                                                         |
|-------------------------------|-----------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `title`                       | `Documentation` for the default site, the site id for any other | What the navbar and the browser tab say                                                                                                                                                                                                                                                                                                                             |
| `tagline`                     | -                                                               | One line under the title on the root page                                                                                                                                                                                                                                                                                                                           |
| `logo`, `favicon`             | the template's                                                  | A `classpath:` or filesystem resource with the site's own mark. Without a favicon the logo is used                                                                                                                                                                                                                                                                  |
| `color-scheme`                | `jeap`                                                          | One of the schemes the site template ships: `jeap`, `neutral`, `high-contrast`. A site does not bring its own CSS - a free-form stylesheet would make every later change to the template able to break it                                                                                                                                                           |
| `environments`                | `dev`, `ref`, `abn`, `prod`                                     | The environments of this site, below                                                                                                                                                                                                                                                                                                                                |
| `publication-schedule`        | `0 5 6-20 * * *`                                                | When the site is regenerated, in the time zone of the service. The default is hourly through the working day, 06:05 to 20:05. **An empty value means never on a schedule**: the site is then published only when something is uploaded to it. There is no separate enabled flag - a schedule that is not there is one that does not run                             |
| `publish-on-upload`           | `true`                                                          | Whether an upload for this site asks for a build of it                                                                                                                                                                                                                                                                                                              |
| `architecture-model-required` | `true`                                                          | Whether this site is published only once the architecture model of its environments has been imported. It has an effect only where an architecture repository is configured for one of them, so a site documenting from other sources never meets it. Such a site is not failed but **postponed** until the first import - see [the import](architecture-import.md) |

The default site is served at the context root of the service, and **every other site below `/site/`** -
`/site/governance/`, and its environments at `/site/governance/dev/`.

That one segment is what keeps the namespaces apart, and it is why **a site id has no reserved names**: it only
has to be a slug, and short enough for the name of the lock its builds take. A site may be called `api`, `dev`
or `prod` without colliding with anything.

What still takes a top-level segment is **the default site's environments** - `/dev/`, `/prod/` - so it is
there that the paths the service answers on itself are unusable. Reserved for them: `api`, `actuator`,
`swagger-ui`, `api-docs`, `webjars`, `error`, `assets`, `img`, and `site`, which is where every other site is
served. The environments of any other site sit below `/site/<id>/` and may be called anything.

An environment of any site may not be called `default` or `static`, whatever its URL: those are names the site
generator uses inside a site's own content tree. All of it is checked while the service starts.

### Environments

An environment is a tree of the same documentation showing the state of one stage. **Exactly one environment of a
site is `main` and exactly one is `latest`, and the service does not start otherwise.**

| Property     | Default              | Description                                                                                                                                 |
|--------------|----------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| `id`         | -                    | The identifier, and the path segment the tree is served under - top-level for the default site, below `/site/<id>/` for any other. A slug   |
| `short-name` | the id in upper case | What the switcher and the banner show, e.g. `DEV`                                                                                           |
| `label`      | the id               | The name a reader sees                                                                                                                      |
| `order`      | `0`                  | Where it appears in the switcher                                                                                                            |
| `main`       | `false`              | **The tree served at the site root**, and the only one search engines are invited to index. Every other tree carries a banner and `noindex` |
| `latest`     | `false`              | **Where the documentation of a component's current state goes**, before it is deployed anywhere - the fast-feedback tree                    |

`main` and `latest` may be the same environment, and usually are not: `prod` is `main`, `dev` is `latest`.

A site **may** be named after an environment of the default site: the site is served below `/site/`, the
environment at the top level, and neither can take the other's URLs. The one name an environment of the default
site may not have is `site` itself.

## Building the documentation

Cross-site: one Node, one workspace root, one timeout per container, however many sites.

```yaml
jeap:
  doc:
    build:
      node-command: /opt/node/bin/node
      node-modules-directory: /opt/jeap-doc/node_modules
      workspace-directory: /app/build
      poll-interval: PT30S
      lock-lease: PT2M
      timeout: PT15M
      max-node-memory: 1024MB
      purge-native-memory: true
      ssg-worker-threads: false
      perf-log: true
      retention: 3
      history-retention: P90D
```

| Property                                | Default            | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
|-----------------------------------------|--------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.doc.build.node-command`           | `node`             | The Node runtime the site is generated with. **An absolute path in a container**: the child process gets an environment built from nothing, and its `PATH` is derived from this value                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `jeap.doc.build.node-modules-directory` | -                  | Where the site template's dependencies are installed - by the image build, see [The site image](site-image.md). The service does not start without them                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `jeap.doc.build.workspace-directory`    | JVM temp directory | Where a build works. **It belongs on storage that belongs to this container alone** - the writable layer of a task on ECS, an `emptyDir` on Kubernetes. Nothing in it has to survive a restart, and a `/tmp` that is a memory-backed tmpfs would put the build into memory                                                                                                                                                                                                                                                                                                                                                                                                      |
| `jeap.doc.build.keep-workspace`         | `false`            | Keeps the workspace of a build instead of deleting it. For reproducing a failure, and nothing else: it is a disk leak with a purpose, and it warns while it is on                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `jeap.doc.build.poll-interval`          | `PT30S`            | How often an instance looks whether a build has been asked for                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `jeap.doc.build.timeout`                | `PT15M`            | How long a build may take before it is given up on                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `jeap.doc.build.lock-lease`             | `PT2M`             | How long a site's build lock is leased for - and therefore **how long after an instance dies its lock survives it**. Far shorter than a build may take, because the lock is extended while the build runs. The shortest accepted is 30 seconds, checked while the service starts                                                                                                                                                                                                                                                                                                                                                                                                |
| `jeap.doc.build.shutdown-timeout`       | `PT15S`            | How long a stopping instance may spend giving up its build. It has to stay below `spring.lifecycle.timeout-per-shutdown-phase`, and the platform's stop timeout has to be above both - see [Generation](generation.md)                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `jeap.doc.build.max-node-memory`        | `1024MB`           | The heap the site generator may use. It has to fit **beside** the JVM in the container. It bounds the **JS side** of a build - the MDX compilation, the plugins, the static generation when it runs in the main process - and **not the bundle phase**: the template bundles with Rspack, whose memory is native and outside the Node heap. The container's limit is the only bound on that phase, and `jeap.doc.container.memory.*` is how to size both numbers - see [Observability](observability.md#the-memory-of-the-container)                                                                                                                                            |
| `jeap.doc.build.purge-native-memory`    | `true`             | Whether the site generator's native allocator hands memory back to the operating system as soon as it is freed. It is about the bundler: Rspack is native code built with mimalloc, which keeps freed pages mapped for the next allocation - so the phases after the bundle run with the whole bundle phase still resident behind them, and the container is sized for the sum. On, a build holds what it is using rather than everything it has used, at the cost of the syscalls that return the pages. It bounds what a build **holds**, never what it allocates. Turn it off if the `[PERF]` lines show the phases after the bundle getting slower than the memory is worth |
| `jeap.doc.build.ssg-worker-threads`     | `false`            | Whether the site generator writes the pages from a pool of worker threads. It makes the static generation about twice as fast, and it is off all the same: each worker thread is a V8 isolate with a heap of its own, and `max-node-memory` bounds an isolate rather than the process - so a pool of them may hold a multiple of that cap, in a container that also holds this JVM. An instance whose container has the room switches it on and reads what it cost off the memory log                                                                                                                                                                                           |
| `jeap.doc.build.perf-log`               | `true`             | Whether the site generator reports how long each phase of a build took and what it did to the Node heap. The lines are logged at `INFO` with the prefix `[PERF]`, nested by phase, each heap reading taken after a full garbage collection - so a build that grows or slows down names the phase responsible. The collections cost a large site seconds. Off, the generator's output is logged at `DEBUG` only                                                                                                                                                                                                                                                                  |
| `jeap.doc.build.retention`              | `3`                | How many published sites are kept per site. **At least 2**, checked while the service starts: the one being served, and the one other instances may still be serving from their publication cache                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `jeap.doc.build.history-retention`      | `P90D`             | How long the record of a build is kept. **The published build of a site is kept whatever its age** - it is what says which site is served                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `jeap.doc.build.history-cron`           | `0 45 2 * * *`     | When old build records are removed, in the time zone of the service                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |

The service **does not start** when the site template's dependencies are missing, when they were installed from a
different `package-lock.json` than the one this version of the service carries, or when Node cannot be run - a
configuration error of an instance belongs in its deployment rather than fifteen minutes into its first build.

## The architecture model

Where the documentation the doc service generates itself comes from. The map is instance-wide and keyed by the
id of an environment, not a property of each site. An environment names a stage, and two sites with a `prod`
environment mean the same stage of the same landscape.

```yaml
jeap:
  doc:
    archrepo:
      environments:
        dev:
          url: https://internal.example.ch/archrepo-service
          client-registration: doc-service
        prod:
          url: https://internal.example.ch/archrepo-service
          client-registration: doc-service
      client:
        connect-timeout: PT5S
        read-timeout: PT30S
    generator:
      max-diagram-nodes: 100
      max-edge-labels: 4
```

| Property                                                           | Default           | Description                                                                                                                                                                                                                                                                                                                                                                                                                                    |
|--------------------------------------------------------------------|-------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.doc.archrepo.environments.<environment>.url`                 | -                 | Where the architecture repository of that stage is. On a platform giving a service two hostnames this is the **internal** one: it is a service calling a service, not a browser following a link                                                                                                                                                                                                                                               |
| `jeap.doc.archrepo.environments.<environment>.client-registration` | -                 | The `spring.security.oauth2.client.registration` entry the token is obtained with. Its client needs `<system-name>_@architecture-model_#read` **on the architecture repository's authorization server** - see [Security](security.md)                                                                                                                                                                                                          |
| `jeap.doc.archrepo.client.connect-timeout`                         | `PT5S`            | How long the client waits for the connection                                                                                                                                                                                                                                                                                                                                                                                                   |
| `jeap.doc.archrepo.client.read-timeout`                            | `PT30S`           | How long the client waits for one response. The budget of a whole import step is `jeap.doc.archrepo.import.timeout`                                                                                                                                                                                                                                                                                                                            |
| `jeap.doc.archrepo.client.retries`                                 | `2`               | How often a failed request is tried again, so three attempts in all. Only a connection failure, a read timeout, a `5xx` or a `429` is retried                                                                                                                                                                                                                                                                                                  |
| `jeap.doc.archrepo.client.retry-delay`                             | `PT0.5S`          | How long to wait before the first retry, doubled for each further one                                                                                                                                                                                                                                                                                                                                                                          |
| `jeap.doc.archrepo.client.retry-jitter`                            | `PT0.25S`         | How much the delay is varied, so that instances whose schedules fire together do not retry in lockstep                                                                                                                                                                                                                                                                                                                                         |
| `jeap.doc.archrepo.client.max-retry-delay`                         | `PT2S`            | The longest a retry waits, however often the delay has been doubled                                                                                                                                                                                                                                                                                                                                                                            |
| `jeap.doc.archrepo.import.cron`                                    | `0 45 5-19 * * *` | When the architecture repository is imported - hourly at a quarter to, so a fresh model stands in front of the publication at five past. **Empty means never** - see [the import](architecture-import.md) and [the scheduled jobs](scheduled-jobs.md)                                                                                                                                                                                          |
| `jeap.doc.archrepo.import.on-startup`                              | `true`            | Whether an environment that has never been imported is imported once while the service starts                                                                                                                                                                                                                                                                                                                                                  |
| `jeap.doc.archrepo.import.timeout`                                 | `PT10M`           | How long one step of one environment may spend fetching. Checked between items, and it has to leave room for one item's retries. A step that runs out of time achieves nothing - the model is all or nothing - so the budget is deliberately more than a landscape needs. It has to stay below `lock-lease`, which is checked while the service starts                                                                                         |
| `jeap.doc.archrepo.import.lock-lease`                              | `PT15M`           | How long the lock of an import survives an instance that dies holding it. **Not a work budget**, and it has to be longer than the timeout                                                                                                                                                                                                                                                                                                      |
| `jeap.doc.archrepo.import.stale-after`                             | `PT2H`            | How old the model may be before a build says so while generating from it                                                                                                                                                                                                                                                                                                                                                                       |
| `jeap.doc.archrepo.import.max-artifact-size`                       | `8MB`             | The largest OpenAPI specification, database schema or message schema answer that is replicated. A bigger one is left where it is, with a warning naming it. It bounds what one answer costs in **memory** as well as what is stored: nothing past it is read off the wire. Zero fails the startup                                                                                                                                              |
| `jeap.doc.generator.max-diagram-nodes`                             | `100`             | How many **other systems** a diagram may draw before the rest are left out - the neighbours of the system context view and of the whitebox view alike. A system exchanging something with a hundred others renders a diagram nobody can read; above this the diagram is truncated, the page says so, and the table below it still lists every one. A system's own components are never cut: the whitebox view draws **all** of them regardless |
| `jeap.doc.generator.max-edge-labels`                               | `4`               | How many names one arrow of a diagram may carry. Above this the arrow shows the count for its kind - `5 Events` - and the page's table names every one of them. It is not only about size: the diagram engine lays a label out by recursion and overflows the browser's stack at about sixty lines, so an uncapped label is a diagram that does not render at all. `0` is legal and means an arrow always shows a count                        |

An environment no entry names has no model-derived documentation. Its tree carries the root page and whatever
was uploaded into it. An instance that reads no architecture model configures none of this, and starts.

Two things fail the startup. Both are configuration errors nobody would notice until a page was missing:

- an entry naming an environment no site declares. It is a typo, and the tree it configures would never be
  generated;
- a `client-registration` naming an entry that does not exist, when the instance has an OAuth2 client registry
  at all. Without the check, that is a failed build an hour later.

The architecture repository is not called while the service starts. It may be deploying, and an instance that
refuses to boot because a neighbour is restarting cannot serve the documentation it already has.

## Publishing and serving

```yaml
jeap:
  doc:
    publication:
      url: https://doc.example.ch
      refresh: PT10S
```

| Property                       | Default | Description                                                                                                                                                    |
|--------------------------------|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.doc.publication.url`     | -       | The origin the documentation is published under, without a path. It is what the sitemap and the page metadata name                                             |
| `jeap.doc.publication.refresh` | `PT10S` | How often an instance re-reads which build of a site is the published one, so it picks up what another instance published without asking the database per file |

The path below the origin is derived rather than configured: it is `server.servlet.context-path`, and below it
the site. A path that has to agree with another value is computed, not written twice.

The generated sites are stored under `jeap.doc.storage.site-prefix` (`sites` by default) and are served to
**anyone who can reach the service** - see [Security](security.md).

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

The service ships `jeapDocDefaultProperties.properties`, contributed to the environment before the context is
built - so a value read while the web server or the lifecycle processor is created sees it. **An instance that
sets any of these itself wins**; they are defaults, not decisions.

| Property                                      | Value                                     | Why                                                                                                                                                                                                                                                                                                                   |
|-----------------------------------------------|-------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.web.headers.content-security-policy`    | a policy allowing only `self` and `data:` | The documentation is self-contained and loads no external content                                                                                                                                                                                                                                                     |
| `jeap.web.headers.additional-content-sources` | empty                                     | The web config starter would otherwise add the OAuth2 issuer's origin to the policy                                                                                                                                                                                                                                   |
| `jeap.web.headers.skip-path-prefixes`         | `/api/,/actuator/`                        | The starter's defaults are `/api` and `-api`, matched against the first path segment - and the default site's trees take one each. An environment called `orders-api` would otherwise be served with no security headers at all                                                                                       |
| `jeap.web.headers.skip-path-suffixes`         | empty                                     | The same                                                                                                                                                                                                                                                                                                              |
| `spring.jpa.open-in-view`                     | `false`                                   | jEAP guideline                                                                                                                                                                                                                                                                                                        |
| `spring.task.scheduling.pool.size`            | `4`                                       | A build runs on the scheduler for as long as `jeap.doc.build.timeout` allows; with one thread it would hold the only one and stop every cron trigger beside it. The architecture imports do **not** run on it - they queue on the doc service's own single import thread, so an import never holds a scheduler thread |
| `spring.web.resources.add-mappings`           | `false`                                   | Spring Boot's catch-all `/**` resource handler is matched just ahead of the one that serves the documentation, and would answer for every page of it                                                                                                                                                                  |
| `server.shutdown`                             | `graceful`                                | An upload is a bundle of up to 50MB; a deployment landing on one would make the client retry a transfer it had all but completed                                                                                                                                                                                      |
| `spring.lifecycle.timeout-per-shutdown-phase` | `20s`                                     | Above `jeap.doc.build.shutdown-timeout`, so giving up a running build is never the phase that is cut short. **This is the number the platform's stop timeout is derived from** - see [Generation](generation.md)                                                                                                      |
| `server.compression.*`                        | on, from 1KB                              | The documentation is text throughout - HTML, the bundles and the part of the search index a page's search bar downloads. An instance behind a compressing CDN can turn it off                                                                                                                                         |

## Related

- [Getting started](getting-started.md)
- [The scheduled jobs](scheduled-jobs.md) - what runs when, and which of them may overlap
- [Uploads](uploads.md)
- [Security](security.md)
