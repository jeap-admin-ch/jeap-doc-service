# Observability

The doc service exposes its meters through the
[jEAP monitoring starter](https://jeap-admin-ch.github.io/docs/jeap-spring-boot-starters/), on
`/actuator/prometheus`. **That endpoint is secured with Basic auth and ships with a placeholder password that
matches no value**, so an instance has to set `jeap.monitor.prometheus.password` or nothing can scrape it.

All names are dotted (`jeap.doc.…`), which Prometheus renders with underscores. A timer already publishes its
count, so there is one meter per event with a `result` tag rather than a counter beside a timer.

**Only the upload timer publishes percentile buckets.** Micrometer's default range for a timer ends at about
thirty seconds, which is the scale an upload takes and is nowhere near the scale of a build or an import - those
are bounded by budgets of ten and fifteen minutes, so every real run would land in the overflow bucket, answer
nothing, and multiply the series of that meter by sixty-seven per tag combination.

## Uploads

| Meter                      | Type         | Tags                                                          |                                                                                                                                                                                                                               |
|----------------------------|--------------|---------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.doc.upload`          | Timer        | `result` = `stored` / `repeated` / `failed`, `reason`, `type` | Every upload that reached the doc service, and how long receiving it took. `repeated` is an idempotent retry - neither a success nor a failure, and counted as itself so that it does not misreport how much a pipeline sends |
| `jeap.doc.upload.bytes`    | Distribution | `type`                                                        | The size of the bundles that were stored. It says whether `jeap.doc.upload.max-size` is set anywhere near reality, before someone hits it                                                                                     |
| `jeap.doc.upload.rejected` | Counter      | `reason`                                                      | Uploads rejected **before** the service read anything of them - an unknown, missing or invalid parameter, or a request that announced no length. They cannot appear in the timer, because nothing was timed                   |

The split matters: a typo in a doc workflow configuration is refused before the upload is even bound, so it would
otherwise be invisible. Each outcome is counted **once** - what the service timed is not counted again where it is
answered.

## Builds

| Meter                             | Type            | Tags                                                                                                             |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
|-----------------------------------|-----------------|------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.doc.build`                  | Timer           | `site`, `result` = `succeeded` / `failed` / `timed_out` / `aborted`, `trigger` = `upload` / `import` / `schedule` / `manual` / `recovery` | The runs: how many, how long, how many failed, per site and per reason for running. **`aborted` is a build an instance gave up on because it was stopping** - a deployment, not a defect, which is why the failure alarm below does not count it. **`timed_out` is a build that ran past `jeap.doc.build.timeout`** - a defect like a failure and alarmed on beside one, but counted apart: it is not put right the way a broken build is, and its duration is the budget every time, so mixed into the failures it would drag their mean towards the budget. `manual` is a run somebody asked for over `/api/sites`, `import` is one the architecture import asked for because that system's model moved, and `recovery` is one that a build left behind by a dead instance asked for. **No `part` tag, deliberately**: a site has as many parts as it has systems, and the sum of this timer over a window is what publishing the documentation cost across all of them - a part label would multiply the series and break that reading. Part-level detail is in the API and in the log |
| `jeap.doc.build.step`             | Timer           | `site`, `step`                                                                                                   | Where the time went. `docusaurus` is the site generator itself - the first thing to look at when a build gets slow                                                                                                                                                                                                                                                                                                                                                                   |
| `jeap.doc.build.part`             | Timer           | `site`, `part`                                                                                                   | **Which part costs what.** Only builds in which the site generator really ran: a build the digest skipped takes a second or two, and averaged in it makes a part that costs three quarters of an hour look cheap - the skips are the counter below. It is a meter of its own rather than a `part` tag on `jeap.doc.build`, deliberately - see the note under this table |
| `jeap.doc.build.pages`            | Gauge           | `site`                                                                                                           | Pages this site is published with, **across its parts**. A sudden drop is a generator problem no failure counter catches, because the build succeeded                                                                                                                                                                                                                                                                                                                                |
| `jeap.doc.build.bytes`            | Gauge           | `site`                                                                                                           | Size of this site as published, across its parts                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `jeap.doc.build.skipped`          | Counter         | `site`, `trigger`                                                                                                | Builds that were asked for and had nothing to publish: the content of the part hashed to what is already being served, so the site generator was never started. **The number that says whether publishing in parts is doing what it is for** - a landscape where nothing changed should count skips and no builds. Tagged with what asked, because that is the question a skip raises: skips from `import` are the split working as intended, skips from `upload` are an upload that changed nothing |
| `jeap.doc.build.last.success.age` | Gauge (seconds) | `site`                                                                                                           | How long ago this site was last **published**, and `NaN` while it never has been. **Not the one to alarm on**, and this is the trap: a part whose content has not moved is never generated, so a site nobody changes goes days without a publication - correctly - and this climbs without bound. It is what says how old the served bytes are, which is a dashboard question                                                                                                         |
| `jeap.doc.build.last.check.age`   | Gauge (seconds) | `site`                                                                                                           | How long ago a part of this site was last published **or found already current**, and `NaN` while none ever was. **The one to alarm on** - it is what says the service is still going through this site's parts. A failed build moves neither gauge: it says the part is *not* up to date, and that is the failure alarm's business                                                                                                                                                   |
| `jeap.doc.build.request.age`      | Gauge (seconds) | `site`                                                                                                           | How long the oldest pending request has been waiting, `0` when none                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `jeap.doc.build.timeout`          | Gauge (seconds) | -                                                                                                                | What `jeap.doc.build.timeout` is set to on this container. Configuration rather than measurement, and published so that *how close a build came to its budget* is a query rather than a number copied into a rule - the copy is what goes stale, silently, on the day the budget is raised. It is also what a dashboard draws the ceiling from                                                                                                                                        |
| `jeap.doc.build.abandoned`        | Counter         | `site`                                                                                                           | Builds found still marked as running although their instance is gone - it was killed rather than stopped, so it never recorded anything. An instance that stops cleanly records `result="aborted"` instead, so this counter means **something killed a container**: the memory it is given is the first thing to look at                                                                                                                                                             |
| `jeap.doc.build.model.read`       | Timer           | `site`, `environment`                                                                                           | Reading the stored architecture model of one environment. Against `jeap.doc.build` it answers *how much of a build is spent loading the landscape*. There is no `result`: a build makes no call to the architecture repository, and a read that fails fails the build. A landscape is held between builds (see [the import](architecture-import.md)), so most samples are near zero and the **count** is how often one was really read - once per environment per import                                                                                                                                                                                                                  |
| `jeap.doc.build.model.systems`    | Gauge           | `site`, `environment`                                                                                            | Systems documented in the last build of that environment **this instance published**. It moves only when a build is published, never for one that read the model and then failed. **The signal no failure counter catches**: an architecture repository that comes back empty succeeds, and only a drop here shows it. Unlike the rows above it, this one is held in memory: it reads `0` on an instance that has not published yet, and again after a restart until the first build |

**Why `part` is a meter and not a tag.** A `part` tag on `jeap.doc.build` would multiply that timer by
the parts of the site on top of its `result` and `trigger` - about 3,000 series for a landscape of fifty
systems - and the label is **unbounded from this service's side**: a part is named after a system in the
imported architecture model, so the upstream decides how many values there are. `jeap.doc.build.part`
costs one series per part instead, and `jeap.doc.build` keeps meaning *what publishing this documentation
cost*. If a ceiling is ever wanted it belongs in a Micrometer `MeterFilter` with `maximumAllowableTags`
on `part`, and not in a smaller tag set.

### The parts

A site is published as several builds, so *how many of them one trigger sets off* and *whether every part is
still being rebuilt* are questions of their own.

| Meter                        | Type                  | Tags                              |                                                                                                                                                                                                                        |
|------------------------------|-----------------------|-----------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.doc.build.triggered`   | Gauge                 | `site`, `trigger`                 | **Parts the last run of that trigger asked to be built.** An import asks for every part of the site, an upload for the one carrying its system. It holds one run's number, so a graph of it reads as one step per run - `0` on the hours where the landscape did not move at all and the import asked for nothing. What actually ran is `jeap.doc.build`, where the parts that had nothing to publish are `result="skipped"` |
| `jeap.doc.parts`             | Gauge                 | `site`                            | Parts this site is published as                                                                                                                                                                                        |
| `jeap.doc.parts.pending`     | Gauge                 | `site`                            | Parts owed a build right now                                                                                                                                                                                           |
| `jeap.doc.part.age`          | Gauge (seconds)       | `site`                            | How long ago the **oldest** published part of this site was built, `0` when none is. It is what says a part has quietly stopped being rebuilt, which the newest publication cannot                                       |

What one publication of a whole site costs is `jeap.doc.build` over the hour the import ran in: every part is
asked for, and the ones whose content has not moved are `result="skipped"` - so the split between skipped and
succeeded is how much of the documentation actually changed.

### The slots, and whether they were used

An instance builds in **passes**: it fills its `jeap.doc.build.max-concurrent-parts` slots from what is owed
and refills the slot of a part that is done at once, until nothing is left that it can build. These are about
the instance rather than about a site, so none of them is tagged - a pass builds whatever is owed, of whichever
site.

| Meter                       | Type    | Tags |                                                                                                                                                                                                                                              |
|-----------------------------|---------|------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.doc.build.slots`      | Gauge   | -    | Builds this instance may run at once - what `max-concurrent-parts` is set to on this container. Configuration rather than measurement, published for the same reason as the timeout: so that a rule and a dashboard need no copy of the number   |
| `jeap.doc.build.slots.busy` | Gauge   | -    | Builds this instance is running right now                                                                                                                                                                                                    |
| `jeap.doc.build.contended`  | Counter | -    | Parts left to another instance, which held their lock. A few are how a fleet shares one queue; a lot of them means the instances are chasing each other rather than spreading out. Registered from the start, so it reads `0` rather than being absent |
| `jeap.doc.build.broken`     | Counter | -    | Parts whose build threw where its own error handling should have covered it. Counted apart from the parts that owed nothing after all: a run full of broken builds must not report itself as one that found nothing to do                       |

**The utilisation is the two gauges over a range**, and there is no meter for it:

```promql
avg_over_time(jeap_doc_build_slots_busy[1h]) / avg_over_time(jeap_doc_build_slots[1h])
```

That is deliberate. A pass runs for as long as the work takes - two hours has been measured - so anything
reported when a pass *ends* says nothing at all while the pass is the thing an operator is looking at. Read
from live samples, the same number is there throughout, and the range is the operator's to choose rather than
the pass's to decide.

**Low utilisation with parts still pending is the thing to chase.** In order of likelihood: fewer parts were
owed than there are slots (nothing to fix), the other instances held the locks - `jeap.doc.build.contended` -
or the pass ran out of parts before it ran out of slots.

A pass also writes one line when it is over, with its own view of the same numbers:

```
A build pass is over after PT4M11S: 26 part(s) built, 1 left to another instance, 0 owed nothing after all,
 0 broken - 91% of 3 slot(s) busy.
```

### The publication, start to finish

A pass is one instance's share of the work. **A publication is the whole of it**: every part of a site, asked
for at once by an operator or by the architecture import, and built across the instances. So no pass is the
number an operator waits for, and no single JVM has it - only the rows do.

Every part of one ask therefore carries the same publication identifier, and these two gauges are read from
those rows once the last of its parts has finished.

| Meter                          | Type            | Tags   |                                                                                                                                                                                          |
|--------------------------------|-----------------|--------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.doc.publication.seconds` | Gauge (seconds) | `site` | **Wall clock of the last completed full publication**, measured from when it was asked for - the queueing is part of what an operator waits for. `NaN` while none has completed, never `0` |
| `jeap.doc.publication.parts`   | Gauge           | `site` | How many parts that publication went through, generated or found unchanged                                                                                                              |

**Completed** means no part of it is still building and none is still owed a build. Until then it is not
reported at all: a publication half-finished would read as one getting slower. The publication before it is
what the gauges go on reporting - the answer is looked for in a window reaching back a week from the site's
newest publication, so a publication still running does not hide the last one that finished.

**Keep this beside the sum, and never conflate them.** `jeap_doc_build_seconds_sum` is the work;
`jeap_doc_publication_seconds` is the elapsed. Their ratio is the parallelism actually achieved:

```
sum(rate(jeap_doc_build_seconds_sum{result="succeeded"}[1h])) * 3600
  / max(jeap_doc_publication_seconds)
```

An upload is not a publication - it asks for one part - so it moves neither gauge. Nor does a build that
recovers a part whose instance died: what that part belonged to is not on the row it left behind, and a
recovery is a new ask.

These gauges are read from the database - all but the systems gauge, see its row - and the two ages are
**ages rather than timestamps**: an age is measured entirely by the service's own clock, where
`time() - <timestamp>` subtracts the service's clock from the scraper's and shows the difference as a false
alert. Reading them from the database is what makes them survive a restart and read the same on every
instance.

## The memory of the container

A build is the largest thing the doc service does, and almost none of it is the JVM: the site generator is a
child process whose bundler allocates natively, so **the JVM meters say nothing about it**. What a container is
sized from is the container's own usage, read from the cgroup files the kernel keeps.

| Meter                                 | Type          | Tags |                                                                                                                                                                                                        |
|---------------------------------------|---------------|------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.doc.container.memory.used`      | Gauge (bytes) | -    | What the container uses, page cache included                                                                                                                                                           |
| `jeap.doc.container.memory.limit`     | Gauge (bytes) | -    | What it is killed at. From the cgroup, or, where the cgroup names none, from the total the JVM sees: on Fargate the container's own `memory.max` says `max`, the limit being enforced a level above it |
| `jeap.doc.container.memory.oom.kills` | Counter       | -    | Processes the kernel has killed in this container for want of memory. **The one to alarm on**                                                                                                          |

They are read **at scrape time** - there is no thread and no interval, and the resolution of the series is the
scrape interval of whoever reads it. That is also where a peak belongs:

```promql
# How close a build came to the limit, over the last quarter of an hour
max_over_time(jeap_doc_container_memory_used_bytes[15m]) / jeap_doc_container_memory_limit_bytes

# Something in this container was killed for want of memory
increase(jeap_doc_container_memory_oom_kills_total[1h]) > 0
```

The kill counter works because of what these kills are: the kernel kills the **site generator**, and the JVM
lives to report it - which is also what turns a build into an exit code of 137. A kill that takes the whole
task instead leaves nothing to scrape, so the platform's own memory alarm is still worth keeping beside this.

**Linux only, and silent elsewhere.** The numbers come from the cgroup files, which exist on Linux and nowhere
else. Where they cannot be read **no meter is registered at all** rather than three that always answer `NaN`:
an alarm cannot tell those from a container that has stopped reporting. On a developer machine the meters read
the cgroup that machine's processes are in, which is the machine rather than a container.

Beside the meters, every published build says what it produced on the line that records it, and a build that
failed says why in its failure reason - `documentation_build.failure_reason`, and `GET /api/sites`:

```text
The documentation site default (246) is published: 6306 pages, 443443925 bytes, PT4M32S of which was the site
generator.
```

**No per-build memory number, on the line or on the row.** This service used to reset the kernel's high-water
mark around each build and record what it found - which was only ever that build's own while one build ran at a
time. With `jeap.doc.build.max-concurrent-parts` above one, each of the overlapping builds wiped what the
others had accumulated, so a row published a confidently exact number that was merely the peak since the last
reset. `max_over_time(jeap_doc_container_memory_used_bytes[15m])` answers the question that number was asked,
over whatever window is wanted, and it needs nothing of a build.

## The architecture import

Its four meters, and the alert to write on them, are on
[The architecture import](architecture-import.md#what-it-reports-about-itself) - beside the explanation of what
a partial run leaves behind, which is what that alert is really about.

| Meter                                           | Kind    | Tags                             | What it is                                                                                                       |
|-------------------------------------------------|---------|----------------------------------|------------------------------------------------------------------------------------------------------------------|
| `jeap.doc.architecture.import.last.success.age` | Gauge   | `environment`, `kind`            | How long ago the last successful import was, `NaN` if there has never been one. **The one to alarm on**           |
| `jeap.doc.architecture.artifacts`               | Gauge   | `environment`, `kind`            | How many things of that kind are stored, `NaN` before the first success. A drop is an arch repo that lost its data |
| `jeap.doc.architecture.import`                  | Timer   | `environment`, `kind`, `result`  | One run: how long it took and how it ended                                                                        |
| `jeap.doc.architecture.import.items`            | Counter | `environment`, `kind`, `outcome` | What a run did: `stored`, `unchanged`, `removed`, `skipped`                                                       |

## What to alarm on

```promql
# A site has not been generated for four hours, on a schedule of several a day - or has never been generated at
# all, which reads as NaN and which a bare `>` would miss. That second case is the one an operator most needs to
# hear about: a site whose generation has been broken since the instance was deployed.
#
# On last.check.age and NOT on last.success.age. A part whose content has not moved is not generated at all, so
# a site nobody changes goes days without a publication and its last success ages without bound - alarming on
# that pages somebody about a site that is exactly right. What says the service is still going through this
# site's parts is a build that ended in either of the outcomes meaning "this part is up to date": published, or
# found to hash to what is already published.
#
# The duration is not optional. The age clause delays itself, because the gauge counts up from zero after each
# build and cannot be true before four hours have passed. The other two are true from the first scrape of a site
# that has never been built - which is every newly deployed site until its first build - so without `for` the
# rule pages on every rollout.
#
#   - alert: DocumentationSiteIsStale
#     for: 4h
#     expr: >
jeap_doc_build_last_check_age_seconds > 4 * 3600
  or absent(jeap_doc_build_last_check_age_seconds)
  or jeap_doc_build_last_check_age_seconds != jeap_doc_build_last_check_age_seconds

# Builds are failing. Deliberately not a negation of "succeeded": a build given up on because its instance was
# stopping is result="aborted", is asked for again on the way down, and is not a defect - counting it here
# would page somebody on every deployment that lands on a build. "timed_out" is a defect and belongs here; it
# is a separate result so that the dashboard and the query below can tell the two apart, not so that a build
# running out of time goes unnoticed.
sum by (site) (rate(jeap_doc_build_seconds_count{result=~"failed|timed_out"}[15m])) > 0

# Builds are running close to their budget - the warning that comes weeks before the alarm above starts firing
# with result="timed_out". Divided by the published budget rather than by a copy of it, so that raising
# jeap.doc.build.timeout moves this rule with it instead of leaving it asserting last month's number.
max by (site) (jeap_doc_build_seconds_max{result="succeeded"})
  / on() group_left() max(jeap_doc_build_timeout_seconds) > 0.75

# A build was asked for and nothing is picking it up
jeap_doc_build_request_age_seconds > 600
```

No `time()` in any of them - which is the point of the age gauges.

**A stale site and an old publication are two different things.** `DocumentationSiteIsStale` is about the
service: it fires when nothing has gone through this site's parts, whether or not anything needed publishing.
How old the published bytes are is `jeap.doc.build.last.success.age`, and on a site nobody changes the honest
answer to that is *weeks* - see [Operating the bucket](operating-the-bucket.md), which no longer expires what a
site is serving.

## The memory of a build, afterwards

**There is no per-build number, and that is deliberate.** A build used to record its own peak on its row: the
kernel's high-water mark, reset when the build started and read when it ended. That is exact while one build
runs at a time, and this service now runs `jeap.doc.build.max-concurrent-parts` of them - each resetting the
mark the others were accumulating into, so a row claimed *exact* about the peak since the last reset.

`max_over_time(jeap_doc_container_memory_used_bytes[15m])` is what to read instead, and
`jeap.doc.container.memory.oom.kills` is what says a container was killed for it. What a *particular* build
cost is not a question this service can answer while several of them share a container, and answering it wrong
is worse than not answering it: `task_total_memory` was sized from that number.

## Related

- [Generating the documentation](generation.md) - what the builds these meters describe actually do
- [The scheduled jobs](scheduled-jobs.md) - the schedules the age gauges are read against
- [Configuration](configuration.md)
- [Operating the bucket](operating-the-bucket.md)
