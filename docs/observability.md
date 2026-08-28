# Observability

The doc service exposes its meters through the
[jEAP monitoring starter](https://jeap-admin-ch.github.io/docs/jeap-spring-boot-starters/), on
`/actuator/prometheus`. **That endpoint is secured with Basic auth and ships with a placeholder password that
matches no value**, so an instance has to set `jeap.monitor.prometheus.password` or nothing can scrape it.

All names are dotted (`jeap.doc.…`), which Prometheus renders with underscores. A timer with a histogram already
publishes its count, so there is one meter per event with a `result` tag rather than a counter beside a timer.

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

| Meter                             | Type            | Tags                                                                                                  |                                                                                                                                                                                                                                                                                                                             |
|-----------------------------------|-----------------|-------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap.doc.build`                  | Timer           | `site`, `result` = `succeeded` / `failed` / `aborted`, `trigger` = `upload` / `schedule` / `manual` / `recovery` | The runs: how many, how long, how many failed, per site and per reason for running. **`aborted` is a build an instance gave up on because it was stopping** - a deployment, not a defect, which is why the failure alarm below does not count it. `manual` is a run somebody asked for over `/api/sites`, and `recovery` is one that a build left behind by a dead instance asked for |
| `jeap.doc.build.step`             | Timer           | `site`, `step`                                                                                        | Where the time went. `docusaurus` is the site generator itself - the first thing to look at when a build gets slow                                                                                                                                                                                                          |
| `jeap.doc.build.pages`            | Gauge           | `site`                                                                                                | Pages the last successful build produced. A sudden drop is a generator problem no failure counter catches, because the build succeeded                                                                                                                                                                                      |
| `jeap.doc.build.bytes`            | Gauge           | `site`                                                                                                | Size of the site last published                                                                                                                                                                                                                                                                                             |
| `jeap.doc.build.last.success.age` | Gauge (seconds) | `site`                                                                                                | How long ago this site was last published, and `NaN` while it never has been. **The one to alarm on** - see the alarm below, because `NaN` is not greater than anything                                                                                                                                                     |
| `jeap.doc.build.request.age`      | Gauge (seconds) | `site`                                                                                                | How long the oldest pending request has been waiting, `0` when none                                                                                                                                                                                                                                                         |
| `jeap.doc.build.abandoned`        | Counter         | `site`                                                                                                | Builds found still marked as running although their instance is gone - it was killed rather than stopped, so it never recorded anything. An instance that stops cleanly records `result="aborted"` instead, so this counter means **something killed a container**: the memory it is given is the first thing to look at    |

These gauges are read from the database, and the two ages are **ages rather than timestamps**, and they are read from the database: an age is measured entirely
by the service's own clock, where `time() - <timestamp>` subtracts the service's clock from the scraper's and
shows the difference as a false alert. Reading them from the database is what makes them survive a restart and
read the same on every instance.

## What to alarm on

```promql
# A site has not been generated for four hours, on a schedule of several a day - or has never been generated at
# all, which reads as NaN and which a bare `>` would miss. That second case is the one an operator most needs to
# hear about: a site whose generation has been broken since the instance was deployed.
#
# The duration is not optional. The age clause delays itself, because the gauge counts up from zero after each
# build and cannot be true before four hours have passed. The other two are true from the first scrape of a site
# that has never been published - which is every newly deployed site until its first build - so without `for`
# the rule pages on every rollout.
#
#   - alert: DocumentationSiteIsStale
#     for: 4h
#     expr: >
jeap_doc_build_last_success_age_seconds > 4 * 3600
  or absent(jeap_doc_build_last_success_age_seconds)
  or jeap_doc_build_last_success_age_seconds != jeap_doc_build_last_success_age_seconds

# Builds are failing. Deliberately result="failed" and not a negation of "succeeded": a build given up on
# because its instance was stopping is result="aborted", is asked for again on the way down, and is not a defect
# - counting it here would page somebody on every deployment that lands on a build.
sum by (site) (rate(jeap_doc_build_seconds_count{result="failed"}[15m])) > 0

# A build was asked for and nothing is picking it up
jeap_doc_build_request_age_seconds > 600
```

No `time()` in any of them - which is the point of the age gauges.

The first one is also what keeps the bucket's lifecycle rule safe: it fires with room to spare before a rule
could expire a site that has stopped being regenerated. See [Operating the bucket](operating-the-bucket.md).

## Related

- [Generating the documentation](generation.md) - what the builds these meters describe actually do
- [Configuration](configuration.md)
- [Operating the bucket](operating-the-bucket.md)
