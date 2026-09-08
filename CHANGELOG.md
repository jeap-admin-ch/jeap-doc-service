# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **The root page's sidebar lists the systems.** A reader landing on a site saw its own pages and a "Systems"
  entry, and had to go through the index to reach anything - because every system is built as a part of its
  own, so its pages are in no tree the shell's build can see. The generator now names them in
  `environments.json` and the site template hangs one link per system under the systems index, found by a
  custom property in its `_category_.json` rather than by its label.
- **A link into another part of the site no longer opens a new tab.** A site is published as several
  Docusaurus builds, and a link that leaves a part is rewritten to `pathname://` so that it escapes the
  broken-link check. `pathname://` is a protocol, and `@docusaurus/Link` decides `isInternal` from the href
  before stripping it - so it read every one of those as external and added `target="_blank"`. Going from the
  systems index into a system, from a system's context view to its neighbour, from a system back to the shell
  and through the footer all collected a tab per hop. Two theme wrappers pass `target="_self"` for a
  `pathname://` href - one for Markdown links, one for the sidebar's way out of a part - and the footer links
  and the navbar logo carry it in the configuration. Links inside a diagram were never affected, and a browser
  test now says so.
- **A system's sidebar starts open down to the pages of a chapter.** Collapsed, it was twelve arc42 chapter
  names and said nothing about what is documented - a reader who does not already know arc42 could not tell
  which chapter held what they came for. The chapters, the building block view's own subtree, the components
  and the message groups are open; a component's own arc42 tree inside them is not, because a system of thirty
  components expanded to every page of each of them is a sidebar nobody can use.
- **An operator can ask for the architecture repository to be imported.** `POST /api/architecture/imports`
  asks for every configured environment and `POST /api/architecture/environments/{environment}/imports` for
  one, both answered `202`. It closes a real gap: the model is imported on a schedule and a build reads what
  was stored, calling the architecture repository not at all - so a correction made there was invisible until
  the next hour, and forcing a publication only published the stored model again. The only way to bring it
  forward was to restart an instance. Its own root rather than a place under `/api/sites`, because an import
  belongs to an environment and every site carrying that environment is generated from it; the roles are the
  site ones, since what an import is for is the documentation those sites publish. Nothing runs on the
  request - the ask goes onto the same single-threaded executor the schedule uses - and the answer says
  `durable: false`, because that queue is the instance's own. `GET /api/architecture/environments` reports
  what the last import of each kind did.
- **A REST API page no longer documents the actuator.** Every jEAP service publishes the operational
  endpoints the platform needs - health, metrics, the AppConfig refresh - and they are in its specification
  without being what a reader of the architecture documentation came for; on a small service they outnumber
  the operations that are. `jeap.doc.generator.rest-api-excluded-paths` is a list of regular expressions that
  have to match a whole path, and it defaults to `/actuator(/.*)?`. A list rather than a rule about the
  actuator, because a component with a management context path of its own is the same question with a
  different answer. A group the exclusions empty is gone rather than left as a heading over an empty table,
  and the page says how many operations it left out so that its count can still be compared with the
  specification. It is applied when a page is written, so a change takes effect on the next build rather than
  on the next import.
- **The site generator's own output is logged at `DEBUG`, the `[PERF]` lines included.** A build writes
  hundreds of lines and a publication is dozens of builds, so at `INFO` the generator's output was most of
  what an instance logged, and none of it answers a question an operator asks - what a run cost is on the
  build record and in `jeap.doc.build.step`, and what a failure was is in the kept tail. The two lines a part
  wrote about the systems it generated went the same way, for the same reason: one per part per environment is
  two hundred of them on a large site. `jeap.doc.build.perf-log` still decides whether the performance lines
  are produced, so they are there the moment the logger is turned down.
- **A part asked for again while a pass is running no longer waits for the whole pass.** A pass offered each
  part once, which is what keeps it from spinning on the parts other instances hold - but it applied to a part
  the pass had already built, for as long as the pass ran. Measured on ApplicationPlatform dev: the hourly
  import wrote 52 requests at 10:45:26Z, the running pass had settled all 52 parts, and nothing touched them
  until it ended at 11:50:57Z. Sixty-five minutes, the last hour of it with no build running at all. A pass
  now remembers when it offered each part and offers it again if a request arrived after that. A part another
  instance holds, or one whose build threw, is still offered once - the first is the spin the rule is for, the
  second a retry loop. It cannot spin either way: a request keeps the instant it was first asked with, so the
  re-offered build claims that row and the next re-offer needs a request written after that claim.
- **A database schema page is bounded, and its partitions are grouped.** One component published 6583 tables,
  of which 6321 are partitions of 260, and its page cost 46 minutes to build on an idle container and
  1 h 31 m under load. A partitioned table is now documented once, under the name of the table with a `_*`
  postfix, saying how many partitions it stands for and their range; the facts row keeps both counts, as
  `6583 (260 after grouping partitions)`. A family is five or more tables of one stem whose columns are
  identical - an `order_v1` beside an `order_v2` whose columns differ stays two tables, and a stem whose
  tables do not all agree is left alone entirely. Grouping happens before either bound applies, and the new
  `jeap.doc.generator.max-schema-table-list` (default `200`) then bounds how many entries the page writes with
  their columns. That is the one bound on the facts rather than on a picture, so where it applies the page
  says how many entries it did not write and links the published schema. Two of the 830 published schemas in
  the estate are shortened by it, by about 60 and 54 entries.
- **The first publication after this ships rebuilds every part.** Grouping changes the generated Markdown, so
  every part's content digest changes and nothing is skipped. That is also the check that it took effect: a
  pass of near-zero `jeap.doc.build.skipped`, and an hour later a pass that skips almost everything again.
- **`jeap.doc.generator.max-schema-tables` is now `max-schema-table-diagram`.** The name said nothing about
  which of a schema page's two renderings it bounds, and there is a second bound beside it now. Renamed
  without a deprecated alias because nothing sets it: `ignoreUnknownFields` has to stay on here, so a renamed
  key is silently ignored rather than refused, and an instance that had set the old one would have fallen back
  to the default without saying so.
- **A full publication has a name, so its wall clock is a number.** Every trigger that asks for *every* part
  of a site - an operator forcing a build, and the architecture import - now mints a publication identifier
  and puts it on each of those requests; the build rows inherit it. `jeap.doc.publication.seconds` and
  `jeap.doc.publication.parts` then report the last publication that is **over**, measured from when it was
  asked for. It is the number an operator waits for and no instance knew: the parts are built across the
  instances, so a pass is one instance's share and the sum of the build durations is the work rather than the
  elapsed time. An upload asks for one part and is no publication.
- **`jeap.doc.build.part` says which part costs what.** A run of fifty-two parts had one of them take three
  quarters of an hour, and nothing in the meters said which: `jeap.doc.build` carries no part, and the build
  rows are the archive rather than what a dashboard sees. It is a meter of its own rather than a `part` tag on
  the build timer, because that tag would multiply the timer by the parts of the site and its values come from
  the imported architecture model - the upstream would decide the cardinality. Only builds in which the site
  generator really ran are recorded; the ones the digest skipped stay with `jeap.doc.build.skipped`.
- **Whether the slots were used is reported live rather than when a pass ends.** `jeap.doc.build.slots` and
  `jeap.doc.build.slots.busy` are what a container may run and is running, and averaged over a range they are
  the utilisation - the number that says a publication took longer than it had to, which no other meter says.
  Reporting it per pass instead was measured to be useless: a pass runs for as long as the work takes, one of
  them for two hours, so a gauge written when it ends says nothing while the pass is the thing being looked at.
  `jeap.doc.build.contended` and `jeap.doc.build.broken` stay and are now incremented as each part is settled,
  for the same reason. One log line per pass still carries its own view of the numbers.
- **The landscape is read once per import instead of once per build.** A build reads the *whole* landscape of
  every environment its part carries, so a site of fifty parts over four environments read four landscapes two
  hundred times - about a third of what a full publication cost. It is now held between builds, keyed on when
  that environment was last read successfully: the import that could have changed a landscape is what drops it,
  so a stale answer is not possible. `jeap.doc.archrepo.import.cache-landscape` switches it off.
- **The largest parts are built first.** A pass ends when its last build ends, so a large part that starts
  last adds the whole of itself to the wall clock. The order within one second of request time is now by size,
  banded to a power of two so that parts of a similar size stay interchangeable - and the pages a part was
  published with is what size means, because a build is about thirty milliseconds a page and the duration of
  its last build says nothing (most of them are skipped by the digest). A part that has never been published
  is treated as the largest there is.
- **Two instances no longer go for the same part.** The pending requests were read in one order - by request
  time, and a full publication writes all of them with one instant - so every instance reached for the same
  part and all but one lost its lock. Within one second of request time the order is now each instance's own;
  between seconds it is still the oldest first, so an upload cannot be overtaken for ever by a landscape that
  is imported every hour. The readiness of a site is also read once per pass over its parts instead of once
  per part.
- **A trigger starts a build pass at once, instead of leaving it to the next poll.** An upload used to wait up
  to a poll interval on the very instance that had just taken it. The pass also runs on a thread of its own
  rather than on the task scheduler, which one build of many minutes used to hold - and with it the
  architecture import and the nightly clean-up. Switchable with `jeap.doc.build.pick-up-on-trigger`, and
  advisory either way: a wake-up that is lost costs nothing, because the request stands and the next poll
  serves it.
- **An instance builds until nothing is owed, instead of one batch per poll interval.** A pass fills every one
  of its `jeap.doc.build.max-concurrent-parts` slots, and refills the slot of a part that is done at once -
  where before it waited for the slowest part of a batch and then for the next poll. On a landscape of fifty
  parts that was most of the wall clock: the slots stood empty for the tail of every batch and the whole of
  every gap. What is owed is read again after each build, so an upload arriving during a pass is served by
  that pass. `jeap.doc.build.poll-interval` now only bounds how long an *idle* instance takes to notice work.
- **A documentation site is published as several Docusaurus builds instead of one.** One build of a whole
  landscape stopped fitting: the memory a build needs grows with the pages it produces, and the component
  documentation of a large landscape puts that beyond any container. A site is now cut into **parts** - one per
  system, carrying that system in every environment, plus a shell part for the site's own pages - and the doc
  service serves the pieces as one site. A part is a set of whole URL subtrees, because a Docusaurus build puts
  its assets under its own base URL; the files every part emits identically are published once per site.
- **A part is only built when its content has moved.** Every build writes the part's content, hashes it and
  compares that with what is published; equal means the site generator is not started at all. The hash is taken
  over the content rather than over the model, so it covers everything a page is made of, and the timestamps of
  the run are taken out of it first.
- **The architecture import is what publishes a site, and a site has no publication schedule of its own.** The
  import runs hourly, and when it stored a landscape it asks for **every** part of every site documenting that
  environment. Which of its systems moved is not asked: a part is one system, and a part whose content has not
  moved is not generated - so the price of not knowing is the content of every part, and never a site rebuilt.
  `jeap.doc.sites.<id>.publication-schedule` is gone.
- **An upload asks for the part it knows about.** An upload names a system, and with a part per system it maps
  to exactly one.
- Every build row now carries its `part` and the `content_digest` it published, and `SKIPPED` is a new state:
  a build that was asked for and had nothing to publish. It is the ordinary outcome now - every import asks
  for every part.
- **The lock a build holds is per part**, so different instances build different parts of a site at the same
  time. Its name is the part's, and the `shedlock.name` column no longer bounds it at 64 characters - a site
  id plus a system slug ran past that at about a twenty-character system name.

### Added

- `jeap.doc.build.max-concurrent-parts` (default `3`) - how many parts one instance builds at a time. A memory
  decision rather than a parallelism one: every concurrent build holds a Docusaurus run in the same container.
- `GET /api/sites/{site}/parts` and `GET /api/sites/{site}/parts/{part}/builds` - what a site is published as,
  what is published for each part and how old it is.
- `POST /api/sites/{site}/parts/{part}/builds` - one part. `POST /api/sites/{site}/builds` now asks for every
  part whether its content moved or not, which is what to use after changing the site template; it has no
  `force` parameter any more, because that is all it does.
- `jeap.doc.build.triggered` - a gauge, per site and trigger, holding **how many parts the last run of that
  trigger asked to be built**. It is what answers how many builds one architecture import sets off.
- The meters that make the split measurable: `jeap.doc.build.skipped`, `jeap.doc.parts`,
  `jeap.doc.parts.pending` and `jeap.doc.part.age` - the last of these being the one that says a part has
  quietly stopped being rebuilt.

### Fixed

- A build's lock is named after the part it locks, and `shedlock.name` was too narrow for one: a site id plus
  a system slug runs past 64 characters at about a twenty-character system name, and that part would never
  have been built.
- **A build somebody asked for by hand is no longer skipped.** Whether the content digest may skip a build was
  read off `trigger_kind`, which records who asked *first* - and two asks for one part are one row. On a site
  fed by the hourly import, parts are pending for a good part of every hour, so forcing a publication answered
  `202`, built every part and skipped every one of them. Whether a build may be skipped is its own column now,
  raised on the request an ask finds pending.
- **A part already owed a build joins the publication that builds it.** Its row kept a null publication, which
  made it invisible to both `not exists` clauses that decide whether a publication is over: the publication
  read as finished while that part was still owed one, and its wall clock stopped before the last part
  finished. The parts of a publication are counted distinct as well - a part aborted by a deployment is asked
  for again with its publication, so one part could have two rows.
- **`DocumentationSiteIsStale` no longer pages about a site that is exactly right.** A part whose content has
  not moved is not generated at all, so a site nobody changes goes days without a publication and
  `jeap.doc.build.last.success.age` climbs without bound. The new `jeap.doc.build.last.check.age` - when a part
  was last published *or found already current* - is what the alarm reads; `last.success.age` stays what it was
  and answers how old the served bytes are.
- **A logo that is not text no longer fails every build of its site.** The content digest read every file as
  UTF-8, and the content of a part is not all text: a PNG or an `.ico` failed the build of that site on every
  part, permanently, with a reason naming the digest rather than the configuration. It hashes bytes now.
- **Only the links that are links are rewritten, and all of them.** The cross-part pass ran one regular
  expression over whole files: an image became a broken one, a fenced diagram was rewritten although its links
  are absolute already, and a titled link, an angle-bracket destination and a link reference definition were
  missed - the three forms that fail the build. It goes line by line now and tracks fences.
- **The way out of a part points at the tree the reader is in.** `sidebars.js` is shared by every docs instance
  of a part, so the "All systems" link could not know which environment a reader was browsing: someone in the
  dev tree landed in the main environment's index. It is built where the environment is in scope, and only
  where that environment has a systems index at all.
- **The entity relationship diagram survives a foreign key spelled in another case**, which used to draw an
  arrow to a second, empty box - a PlantUML code is case-sensitive - and a column name carrying a quote, which
  the escaping turned into PlantUML's own line comment and so removed from the diagram altogether.
- **The "Published schema" row is a link.** A `contentUrl` is the architecture repository's own path, and
  `Md.linkOrCode` links only what it can, so the row rendered as a code span while the paragraph below it told
  the reader to open it.
- A part left to another instance is reported: `jeap.doc.build.contended` is registered from the start rather
  than on first use, so `rate()` over it answers zero instead of nothing. A build that throws where its own
  error handling should have covered it is counted as `jeap.doc.build.broken` rather than among the parts that
  owed nothing after all.
- A part reports **its own** bytes and files. Every part build writes the same `assets/**` and `img/**` to one
  prefix of the site and counted them as its own, so the size of a fifty-two-part site carried fifty-one extra
  copies of that bundle.
- The two truncation notes under a component's diagrams agree with their count - they said "1 of the 2 tables
  are left out", which is what every reader saw, because a bound is crossed one table at a time.

### Removed

- **The site search is gone.** The offline search plugin built its index out of the pages of one build, so a
  site published as several builds would get one index per build, and a reader searching in one of them would
  find only that part of the documentation. A search over the whole documentation will be served by the doc
  service itself, which has the text of every page it writes; until then the site has none.
- **The sitemap is gone**, for the same reason one step further along: the plugin writes `sitemap.xml` at the
  root of the build that ran, so every part emitted one and only the shell's was ever served - naming the
  environment root pages, the systems index and the about pages, and none of the documentation. What would be
  right is a sitemap index the shell writes from what the other parts emitted, which is the doc service's to
  write rather than the generator's.
- **The per-build memory peak is gone**, from the row, the log line, the API and the page describing the
  documentation. It reset the kernel's high-water mark around each build, which is exact only while one build
  runs at a time: with several parts building at once each of them wiped what the others had accumulated, so a
  row published a confidently exact number that was the peak since the last reset - and that number is what
  `task_total_memory` was being sized from. `max_over_time(jeap_doc_container_memory_used_bytes[15m])` answers
  the question over whatever window is wanted. The columns `memory_peak_bytes`, `memory_limit_bytes` and
  `memory_peak_exact` are left in the table unmapped and are dropped a release later, so that an instance of
  the version before this one goes on inserting rows while a deployment is half-done.
- **The bucket's age rule over the sites is gone from the documentation.** A published part is written once and
  then left alone, so an age rule over `jeap-doc-content=site` expires exactly the documentation nobody has had
  to touch - and the next build finds its digest unchanged, publishes nothing, and never heals it. The
  service's own retention is what removes a superseded publication.

## [1.3.0] - 2026-09-04

### Added

- **The component documentation is generated**, as an arc42 tree below each component's page in its system:
  what the component is, the components and systems it exchanges something with, the entity relationship
  diagram of its database, the overview of its REST API by group with the link to the Swagger UI of the
  architecture repository, and the messages it produces and consumes.
- **The replicated OpenAPI specifications and database schemas are rendered.** They were already being
  imported; this is the first version that reads them.
- `jeap.doc.generator.max-context-components` (default `40`) and `jeap.doc.generator.max-schema-tables`
  (default `100`) bound the two new diagrams. Every page that carries a bounded diagram lists in full what
  the diagram had no room for.
- **A build that runs past `jeap.doc.build.timeout` is counted apart from one that broke**, as
  `jeap.doc.build{result="timed_out"}`. It is a defect just as much and the failure alarm now counts both -
  but it is not put right the way a broken build is, and its duration is the budget every time, so folded in
  with the failures it dragged their mean towards the budget and was invisible in every other respect.
- `jeap.doc.build.timeout` is published as a gauge in seconds, so that *how close a build came to its budget*
  is a query rather than a number copied into an alerting rule - the copy is what goes stale, silently, on the
  day the budget is raised. See [Observability](docs/observability.md) for the rule it is meant for.

### Changed

- A generation run reads the replicated artifacts **one component at a time** rather than a whole system's at
  once, so that a component count's multiple of `jeap.doc.archrepo.import.max-artifact-size` is never live in
  a build. `ArchitectureArtifactRepository.findAll` is gone with it - one artifact per lookup is now the only
  way to read one.
- The **Database Schema** page is written as soon as the architecture model says the component has a schema,
  replicated or not, the way the REST API page already was. Until the schema arrives the page carries its
  version and the link to the published schema and says that there is no diagram yet.

### Fixed

- The notes saying that a diagram left a neighbour out printed `%d` instead of the count, and said
  "1 further systems" where one was left out.
- A column of an array type was rendered with its brackets replaced - `text[]` came out as `text()`, which is
  a different type. Only a **doubled** bracket is now replaced, which is the pair PlantUML reads as a link.

## [1.2.0] - 2026-09-03

### Added

- **The system documentation is generated** as one arc42 tree per system: components, messages, schemas, diagrams.
- **The architecture repository is imported hourly into the doc service's own database**; a build reads that copy.
- **arc42 is a module of its own**, `jeap-doc-template-arc42`, and every page goes through `jeap-doc-markdown`.
- **An "About This Documentation" page** per environment names what the model contributed and what the build cost.
- **Every site but the default is served below `/site/`**: the URL of every named site changes, with no redirect.

## [1.1.1] - 2026-09-03

### Dependencies
- **@matfsw/docusaurus-plantuml-plugin**: 1.7.0 → 1.7.1 (patch)

## [1.1.0] - 2026-09-02

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.5.0 → 40.7.0 (minor)
- **serialize-javascript**: 7.1.0 → 7.1.1 (patch)
- **docusaurus-plugin-llms**: 0.5.1 → 0.6.0 (minor)
- **@matfsw/docusaurus-plantuml-plugin**: 1.6.2 → 1.7.0 (minor)

## [1.0.0] - 2026-08-28

### Dependencies

- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.4.0 → 40.5.0 (minor)
- **react-dom**: 19.2.0 → 19.2.8 (patch)
- **react**: 19.2.0 → 19.2.8 (patch)
- **minimatch**: 9.0.9 → 10.2.6 (major)

## [0.5.0] - 2026-08-28

### Added

- **The doc service generates and serves the documentation site.** It runs the site generator over a template it
  ships, publishes the result to the object storage and serves it - one tree per environment, with an
  environment switcher, offline search, `llms.txt` and diagrams rendered from their source in the browser.
- **Documentation sites are configured** under `jeap.doc.sites`; an instance that configures none gets the
  `default` site. A build is asked for by an upload and by a schedule, runs once per site across all instances,
  and is recorded in `documentation_build` - the newest successful row *is* the published site.
- **A site administration API** under `/api/sites`: `POST /api/sites/{site}/builds` asks for a site to be
  published, and the `GET` endpoints answer what each site is configured to do, what is pending, what is running
  and what has been built. Asking is not building - the ask leaves the same collapsing request an upload leaves,
  recorded as the new `MANUAL` build trigger. Two new roles: `<system-name>_@sites_#admin` for the ask and
  `<system-name>_@sites_#read` for the reading.
- **The site search is scoped to the environment being read.** The index is split one part per environment and
  the search box takes the part from the page it is on, so a query answers with the tree the reader is in
  instead of with the same page once per environment. The search page carries a selector for changing it.
- **Meters for the uploads and the builds** under `jeap.doc.*`, with the alarms in `docs/observability.md`.
- New pages: [Generation](docs/generation.md), [The site image](docs/site-image.md),
  [Observability](docs/observability.md), [Operating the bucket](docs/operating-the-bucket.md).

### Changed

- **An instance that is stopped gives up its build instead of being cut off**: the generator is destroyed, the
  build is recorded as `ABORTED` rather than `FAILED`, its lock is given back and it is asked for again. A build
  left behind by an instance that was *killed* is picked up as `RECOVERY` - once, so it cannot become a crash
  loop. In-flight requests are finished on shutdown (`server.shutdown=graceful`).
- **Everything outside `/api` is now the documentation site**, served to anyone who can reach the service, and
  the Content Security Policy allows what the site generator emits (`'unsafe-inline'`, `'wasm-unsafe-eval'`,
  `worker-src 'self' blob:`). An instance that overrode the policy has to follow.
- **The domain no longer depends on a metrics library, on Jackson or on a JSON format.** The meters moved into a
  new `jeap-doc-metrics` adapter; writing what the site template reads moved into `jeap-doc-sitegenerator`. An
  instance that lists the modules by hand has to add `jeap-doc-metrics`.

### Fixed

- **An upload naming a site the instance does not configure is rejected** rather than stored and published
  nowhere: `400 UNKNOWN_SITE`, naming the sites that do exist.

### Security

- **The site template's transitive npm dependencies are pinned past their advisories** through `overrides` in
  `package.json`. `image-size` has no fixed release at all - why that is not exploitable here is written down in
  `.trivyignore`.

### Requires

- **A stop timeout above the shutdown budget on the platform**: `stopTimeout: 90` on ECS,
  `terminationGracePeriodSeconds: 90` on Kubernetes. The ECS default of 30 seconds turns the shutdown into a
  kill. See [Generation](docs/generation.md).
- **Node and the site template's dependencies in the image.** The service does not start without them, nor when
  they were installed from a different lockfile than the one it carries. See
  [The site image](docs/site-image.md).
- **Node 24, Docker and Chrome to build this repository** - the site is really generated and really driven in a
  browser by the tests.

### Dependencies
- **ch.admin.bit.jeap:jeap-spring-boot-parent**: 40.2.0 → 40.4.0 (minor)

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
