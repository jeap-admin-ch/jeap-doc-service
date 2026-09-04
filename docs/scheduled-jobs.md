# The scheduled jobs

Everything the doc service does on its own, without a request: six jobs, each with its schedule in the
configuration rather than in an annotation, each logged while the service starts. **Why is this site not
updating** and **why is the model old** are answered by the first lines of the log, not by reading the
configuration of a running service.

| Job                                                            | Property                                   | Default           | What it does                                                                                                                                                                                                                                                                |
|----------------------------------------------------------------|--------------------------------------------|-------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [Build poll](generation.md)                                    | `jeap.doc.build.poll-interval`             | `PT30S`           | Looks whether a build has been asked for and publishes **at most one site** per tick per instance                                                                                                                                                                           |
| [Publication schedule](generation.md), per site                | `jeap.doc.sites.<id>.publication-schedule` | `0 5 6-20 * * *`  | Asks for a build of that site. Hourly at five past, through the working day. **Empty means never**: the site is then published only when something is uploaded to it                                                                                                        |
| [Architecture import](architecture-import.md), per environment | `jeap.doc.archrepo.import.cron`            | `0 45 5-19 * * *` | Imports the architecture model, the OpenAPI specifications, the database schemas and the Avro schemas of the message type versions of one environment. Hourly at a quarter to, so a fresh model stands in front of the publication at five past. **Empty means never**      |
| [Architecture import catch-up](architecture-import.md)         | `jeap.doc.archrepo.import.on-startup`      | `true`            | Once, after the service is up: imports every environment and kind that has **never** been imported, so the first build after a deployment finds a model                                                                                                                     |
| [Upload housekeeping](uploads.md)                              | `jeap.doc.upload.housekeeping.cron`        | `0 30 2 * * *`    | Removes uploads last received more than `jeap.doc.upload.housekeeping.retention` (`P14D`) ago, whatever state they are in. **The database only** - the bundles are expired by a lifecycle rule of the bucket. `jeap.doc.upload.housekeeping.enabled: false` switches it off |
| [Build history housekeeping](generation.md)                    | `jeap.doc.build.history-cron`              | `0 45 2 * * *`    | Removes the record of builds that finished more than `jeap.doc.build.history-retention` (`P90D`) ago - **except the published build of each site**, which is what says which site is served                                                                                 |

Every cron expression is a Spring six-field one and is read **in the time zone of the service**. The two
nightly jobs are a quarter of an hour apart on purpose: they are both a delete over a large table, and one at a
time is enough.

## Which thread, and which lock

| Job                          | Thread                                                          | Lock                                                                                                                   |
|------------------------------|-----------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| Build poll                   | The scheduler pool, `spring.task.scheduling.pool.size` (4)      | `documentationBuild-<site>`, leased for `jeap.doc.build.lock-lease` (`PT2M`)                                           |
| Publication schedule         | The scheduler pool                                              | none - it only sets the site's request flag, and a burst of triggers during a build produces exactly one follow-up run |
| Architecture import          | `architectureImportTaskExecutor`: **one thread**, bounded queue | `architectureImport-<environment>-<kind>`, leased for `jeap.doc.archrepo.import.lock-lease` (`PT15M`)                  |
| Architecture import catch-up | The same executor                                               | The same locks                                                                                                         |
| Upload housekeeping          | The scheduler pool                                              | `documentationUploadHousekeeping`, leased for 30 minutes                                                               |
| Build history housekeeping   | The scheduler pool                                              | `documentationBuildHousekeeping`, leased for 30 minutes                                                                |

Three things follow from that table, and each of them is deliberate:

- **An import never runs on a scheduler thread.** The cron only hands the environment to the import executor
  and returns within a millisecond. An import takes minutes, the cron fires for every environment in the same
  second, and the scheduler pool is what the build poll and every site's publication trigger run on - imports
  running inline would hold all of them, and a build asked for at a quarter to would be looked for when the
  last import ended.
- **Every job is kept to one instance by a lock in the database**, timed by the database rather than by the
  instances, which do not share a clock. A lease says how long a lock survives an instance that dies holding
  it and **not** how long the work may take: it is extended while the work runs.
- **An instance that does not get a lock does not queue.** A build request stays standing and the next poll
  tries again 30 seconds later; a housekeeping job skips the night; an import skips the hour, because another
  instance is importing into the same database and what it stores is what this instance's builds read either
  way.

## Which of them may overlap

The build lock is per **site**, the import lock is per **environment and kind**, and the housekeeping locks are
one each. So nothing stops these from running at the same moment - on two threads of one instance, or on two
instances:

|                                                      |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
|------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| A build and an import of an environment it documents | **Allowed, and it has to be.** A build reads the architecture model out of this service's own database and makes no call to the architecture repository at all, so an import can neither slow a build down nor fail one. What keeps it safe is that the landscape is read as one snapshot of the database, and that a build generates from the model as it stood when it started - see [reading a landscape while one is being written](architecture-import.md#reading-a-landscape-while-one-is-being-written) |
| Builds of two different sites                        | Allowed, on two instances. One instance builds one site per tick: a build is a process that wants a core, and three pending sites must not become three of them in one container                                                                                                                                                                                                                                                                                                                               |
| Two builds of the same site                          | Only where a lock lease was lost while the build carried on. Harmless: each build publishes under its own identifier and the newest successful one wins                                                                                                                                                                                                                                                                                                                                                        |
| The nightly jobs and anything else                   | Allowed. Neither touches what a build or an import reads: uploads that have not been generated from in a fortnight, and build records that are not the published one                                                                                                                                                                                                                                                                                                                                           |

An import that is still running when the instance starts stopping **gives up** - it is asked between two
requests - and the next schedule imports the rest. A build that is running is given up on too, recorded as
aborted, and asked for again, within `jeap.doc.build.shutdown-timeout`.

## What is not a scheduled job

Time-based behaviour that gets looked for on this page anyway, and none of it has a thread of its own:

|                           | Property                               | Default | When it happens                                                                                                                                                            |
|---------------------------|----------------------------------------|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Publication cache refresh | `jeap.doc.publication.refresh`         | `PT10S` | Lazily, on the request that finds the cached publication older than that - so an instance picks up what another one published without asking the database on every request |
| Workspace sweep           | -                                      | -       | Before each build, over the workspaces of builds that are no longer running                                                                                                |
| Published site retention  | `jeap.doc.build.retention`             | `3`     | After each successful build. **At least 2**: the site being served, and the one other instances may still serve from their publication cache                               |
| Upload takeover timeout   | `jeap.doc.upload.in-progress-timeout`  | `PT2M`  | On an upload that finds the same upload id already in progress                                                                                                             |
| Model staleness warning   | `jeap.doc.archrepo.import.stale-after` | `PT2H`  | While a build generates from a model older than that. It tolerates one failed import and warns on the second                                                               |

## What to watch

Two age gauges say that a schedule has stopped working, which is invisible from the outside because the site
goes on being served either way:

| Gauge                                                   | Says                                                                              |
|---------------------------------------------------------|-----------------------------------------------------------------------------------|
| `jeap_doc_architecture_import_last_success_age_seconds` | Nothing has been imported for that long - or never has been, which reads as `NaN` |
| `jeap_doc_build_last_success_age_seconds`               | No site has been published for that long                                          |

Both, and what to alarm on, are in [Observability](observability.md).

## Related

- [Importing the architecture repository](architecture-import.md) - what the import job does, and what happens when it cannot
- [Generating the documentation](generation.md) - what a build does, and what triggers one
- [Uploads](uploads.md) - what happens to an uploaded bundle before a build
- [Configuration](configuration.md) - every property, with its default
- [Observability](observability.md) - the meters and what to alarm on
