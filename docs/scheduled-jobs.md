# The scheduled jobs

Everything the doc service does on its own, without a request: seven jobs, each with its schedule in the
configuration rather than in an annotation, each logged while the service starts. **Why is this site not
updating** and **why is the model old** are answered by the first lines of the log, not by reading the
configuration of a running service.

| Job                                                            | Property                                   | Default           | What it does                                                                                                                                                                                                                                                                |
|----------------------------------------------------------------|--------------------------------------------|-------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| [Build poll](generation.md)                                    | `jeap.doc.build.poll-interval`             | `PT30S`           | Asks this instance to look whether a build has been asked for. What it finds it builds in a **pass** - every slot full until nothing is owed - so this is the latency of an **idle** instance and not the pace of a queue. A trigger asks for a pass at once as well, unless `jeap.doc.build.pick-up-on-trigger` is off |
| [Architecture import](architecture-import.md), per environment | `jeap.doc.archrepo.import.cron`            | `0 45 5-19 * * *` | Imports the architecture model, the OpenAPI specifications, the database schemas and the Avro schemas of the message type versions of one environment, and **asks for every part of every site documenting it to be published**. Hourly. There is no separate publication schedule: this is it. **Empty means never**      |
| [Architecture import catch-up](architecture-import.md)         | `jeap.doc.archrepo.import.on-startup`      | `true`            | Once, after the service is up: imports every environment and kind that has **never** been imported, so the first build after a deployment finds a model                                                                                                                     |
| [Upload housekeeping](uploads.md)                              | `jeap.doc.upload.housekeeping.cron`        | `0 30 2 * * *`    | Removes uploads last received more than `jeap.doc.upload.housekeeping.retention` (`P14D`) ago, whatever state they are in. **The database only** - the bundles are expired by a lifecycle rule of the bucket. `jeap.doc.upload.housekeeping.enabled: false` switches it off |
| [Build history housekeeping](generation.md)                    | `jeap.doc.build.history-cron`              | `0 45 2 * * *`    | Removes the record of builds that finished more than `jeap.doc.build.history-retention` (`P90D`) ago - **except the published build of each part**, which is what says which site is served                                                                                 |
| [Site reconcile](generation.md)                                | `jeap.doc.build.reconcile-cron`            | `0 15 6-18/4 * * *` | Asks for every part of the sites **no architecture import publishes** - a site whose environments have no architecture repository has no other trigger than an upload or an operator, and would go on serving what an earlier release generated. Nearly free: a part whose content has not moved is not generated. `-` switches it off |

Every cron expression is a Spring six-field one and is read **in the time zone of the service**. The two
nightly jobs are a quarter of an hour apart on purpose: they are both a delete over a large table, and one at a
time is enough.

## Which thread, and which lock

| Job                          | Thread                                                          | Lock                                                                                                                   |
|------------------------------|-----------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| Build poll                   | The scheduler pool, `spring.task.scheduling.pool.size` (4) - and it only asks for a pass | none - it takes no lock and returns within a millisecond |
| Build pass                   | `documentation-build-pickup`: **one thread**, plus one per slot while it builds | `documentationBuild-<site>/<part>` per part, leased for `jeap.doc.build.lock-lease` (`PT2M`) |
| Architecture import          | `architectureImportTaskExecutor`: **one thread**, bounded queue | `architectureImport-<environment>-<kind>`, leased for `jeap.doc.archrepo.import.lock-lease` (`PT15M`)                  |
| Architecture import catch-up | The same executor                                               | The same locks                                                                                                         |
| Upload housekeeping          | The scheduler pool                                              | `documentationUploadHousekeeping`, leased for 30 minutes                                                               |
| Build history housekeeping   | The scheduler pool                                              | `documentationBuildHousekeeping`, leased for 30 minutes                                                                |
| Site reconcile               | The scheduler pool - it only writes build requests and returns  | none - the per-part request is what collapses two instances asking for the same part                                   |

Three things follow from that table, and each of them is deliberate:

- **Neither an import nor a build pass runs on a scheduler thread.** Both only hand the work over and return
  within a millisecond. An import takes minutes and the cron fires for every environment in the same second; a
  pass takes as long as the parts it builds. Either of them running inline would hold the scheduler threads
  that every other job of this service is on, and a build asked for at a quarter to would be looked for when
  the last import ended.
- **Every job is kept to one instance by a lock in the database**, timed by the database rather than by the
  instances, which do not share a clock. A lease says how long a lock survives an instance that dies holding
  it and **not** how long the work may take: it is extended while the work runs.
- **An instance that does not get a lock does not queue.** A pass that finds a part's lock held takes the next
  part instead, and leaves that one to the instance building it - the request stays standing either way. A
  housekeeping job skips the night; an import skips the hour, because another instance is importing into the
  same database and what it stores is what this instance's builds read either way.

## Which of them may overlap

The build lock is per **part**, the import lock is per **environment and kind**, and the housekeeping locks are
one each. So nothing stops these from running at the same moment - on two threads of one instance, or on two
instances:

|                                                      |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
|------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| A build and an import of an environment it documents | **Allowed, and it has to be.** A build reads the architecture model out of this service's own database and makes no call to the architecture repository at all, so an import can neither slow a build down nor fail one. What keeps it safe is that the landscape is read as one snapshot of the database, and that a build generates from the model as it stood when it started - see [reading a landscape while one is being written](architecture-import.md#reading-a-landscape-while-one-is-being-written) |
| Builds of two different parts                        | Allowed, and it is the point. One instance builds `jeap.doc.build.max-concurrent-parts` at a time - a build is a process that wants a core, so fifty pending parts must not become fifty of them in one container - and the other instances build other parts at the same time                                                                                                                                                                                                                                  |
| Two builds of the same part                          | Only where a lock lease was lost while the build carried on. Harmless: each build publishes under its own identifier and the newest successful one wins                                                                                                                                                                                                                                                                                                                                                        |
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
| `jeap_doc_build_last_check_age_seconds`                 | Nothing has gone through any site's parts for that long                           |

**Not `jeap_doc_build_last_success_age_seconds`** for the second one. A part whose content has not moved is
never generated, so a site nobody changes goes days without a publication and is nonetheless being looked at
every hour; the gauge to alarm on is the one that counts a part found already current as a check.

Both, and what to alarm on, are in [Observability](observability.md).

## Related

- [Importing the architecture repository](architecture-import.md) - what the import job does, and what happens when it cannot
- [Generating the documentation](generation.md) - what a build does, and what triggers one
- [Uploads](uploads.md) - what happens to an uploaded bundle before a build
- [Configuration](configuration.md) - every property, with its default
- [Observability](observability.md) - the meters and what to alarm on
