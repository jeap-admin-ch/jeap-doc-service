# Architecture

The doc service is built as **ports and adapters** (hexagonal architecture): the domain in the centre holds the
model and the business logic, it declares the *ports* it needs as interfaces, and the technical *adapters* around
it implement those ports or drive the domain through them. The domain therefore depends on no framework detail,
and a technology can be replaced without touching the business logic.

Beside the domain and the adapters there is a third kind of module: a **structure template plugin**. It supplies
a way of structuring documentation - arc42 is the one that ships - and it is neither the centre of the hexagon nor a
technology behind a port. [Structure templates](structure-templates.md) describes it.

```mermaid
flowchart LR
    Pipeline[Doc pipeline] -->|PUT /api/uploads/docs| Web
    Browser[Browser] -->|GET| Web
    subgraph Adapters
        Web[jeap-doc-web<br/>driving adapter]
        Persistence[jeap-doc-persistence<br/>driven adapter]
        Storage[jeap-doc-objectstorage<br/>driven adapter]
        Generator[jeap-doc-sitegenerator<br/>driven adapter]
        Html[jeap-doc-html<br/>driven adapter]
        ArchRepo[jeap-doc-archrepo<br/>driven adapter]
        Reactions[jeap-doc-reactionobserver<br/>driven adapter]
    end
    subgraph Templates[Structure template plugins]
        Arc42[jeap-doc-template-arc42]
    end
    Web --> Domain[jeap-doc-domain<br/>model, services, ports,<br/>StructureTemplate]
    Domain -.->|port| Persistence
    Domain -.->|port| Storage
    Domain -.->|port| Generator
    Domain -.->|port| Html
    Domain -.->|port| ArchRepo
    Domain -.->|port| Reactions
    Arc42 -.->|implements StructureTemplate| Domain
    Generator -.->|injected| Templates
    Web -.->|injected| Templates
    Persistence --> Db[(PostgreSQL)]
    Storage --> S3[(S3 object storage)]
    Generator --> Node[Site generator<br/>child process]
    ArchRepo --> Model[(Architecture repository)]
    Reactions --> Observer[(Reaction observer)]
    ArchRepo -.->|transport| Upstream[jeap-doc-upstream<br/>support]
    Reactions -.->|transport| Upstream
```

## Modules

| Module                      | Role            | Contents                                                                                                                                                                                                                                                                                                    |
|-----------------------------|-----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap-doc-domain`           | domain          | The model of the documentation, the services acting on it and the ports it needs - see [its packages](#the-packages-of-the-domain)                                                                                                                                                                          |
| `jeap-doc-markdown`         | support         | How a page is written: Markdown, front matter, `_category_.json` - **and the escaping**. No dependencies at all                                                                                                                                                                                             |
| `jeap-doc-template-arc42`   | plugin          | arc42: its twelve chapters, its structural rules, and the pages generated into them from the architecture model                                                                                                                                                                                             |
| `jeap-doc-persistence`      | driven adapter  | Spring Data JPA on PostgreSQL (the uploads, the builds, the architecture model and what is replicated beside it), and the Flyway migrations                                                                                                                                                                 |
| `jeap-doc-objectstorage`    | driven adapter  | S3 over the jEAP object storage starter, and the startup check of the bucket                                                                                                                                                                                                                                |
| `jeap-doc-html`             | driven adapter  | Reads an uploaded HTML document as text, behind `HtmlText`. **The only module that may hold an HTML parser**: uploaded HTML is the least trustworthy input this service has, so the dependency that reads it is one small module's rather than everything's |
| `jeap-doc-sitegenerator`    | driven adapter  | Produces the site: the build workspace, what the site template reads, the site template itself, the generator process                                                                                                                                                                                       |
| `jeap-doc-upstream`         | support         | How another jEAP service is called and replicated: the client and its token, the bounded conditional `GET`, entity tags, redirects, content URLs, one exception with a retry policy over it. **No bean, no auto-configuration, no properties** - and what the two upstream adapters below may not duplicate |
| `jeap-doc-archrepo`         | driven adapter  | Everything about the architecture repository: the client of its `/docs-api` behind the three upstream ports of [the import](architecture-import.md), and the reading of a replicated artifact behind `ArchitectureArtifactContent`                                                                          |
| `jeap-doc-reactionobserver` | driven adapter  | Everything about the reaction observer: the client of its replication API behind `ReactionGraphUpstream`, which [the import](architecture-import.md) reads the reaction graphs of an environment through                                                                                                    |
| `jeap-doc-metrics`          | driven adapter  | The Micrometer meters behind the `UploadMetrics`, `BuildMetrics` and `ArchitectureImportMetrics` ports, and the container memory gauges, which are read in this module and have no port in the domain - nothing in the domain asks what the container holds                                                 |
| `jeap-doc-site`             | resources       | The site generator's own application - no Java. Read from the classpath, never from a directory beside the jar                                                                                                                                                                                              |
| `jeap-doc-web`              | driving adapter | The Spring Boot application: REST API, OpenAPI, security, and the documentation it serves                                                                                                                                                                                                                   |
| `jeap-doc-service-instance` | packaging       | POM-only module a project inherits from, or depends on, to create its own doc service instance                                                                                                                                                                                                              |

### The packages of the domain

The domain is the largest module, so it is divided by what a class is about rather than by what it is:

| Package | What is in it |
|---------|----------------|
| `…doc.domain` | The builds and the sites: the runner, the trigger, the schedules, the site configuration and what is published |
| `…doc.domain.upload` | Everything about an upload - the descriptor, the service, the states, the housekeeping. It asks the rest of the domain for one thing: a build |
| `…doc.domain.architecture` | The architecture model as a page needs it: the `Documented…` records and the enums they carry |
| `…doc.domain.architecture.view` | `SystemContext` and `WhiteboxView` - what a diagram of one system shows, computed across the whole landscape |
| `…doc.domain.architecture.imports` | How that model is replicated: the job, its schedule, the four kinds and the step for each, the deadline and the outcome |
| `…doc.domain.custom` | The documentation a team uploaded, as the service holds it: a set, its pages, the subject it documents, and how a page's front matter is rewritten |
| `…doc.domain.port` | Every interface the domain needs from the outside, and the records they answer with |
| `…doc.domain.template` | The `StructureTemplate` plugin point - see [Structure templates](structure-templates.md) |

**`custom` and `architecture` do not know about each other either.** One is what a team wrote and the other
is a replica of an upstream; nothing links them in the database, and they are joined per system while a part
is generated - see [The documentation a team writes](custom-documentation.md). `CustomDocumentation` and
`CustomPages` are values of one build rather than beans, so the *one adapter per port* rule does not reach
them: they are not in `port`.

**`architecture` does not depend on `architecture.imports`, and that is the point of the split.** The records a
page is written from do not know they were replicated, so how the landscape is fetched can change without
touching them - and `Deadline`, `ArchitectureImportStep` and `StoredArchitectureModel` are package-private
because nothing outside the replication has any use for them.

### Rules the modules follow

- **The domain module depends on no adapter module, on no web framework, on no driver - and on no infrastructure
  library.** Everything it needs from the outside is an interface in `ch.admin.bit.jeap.doc.domain.port`. That
  rule is not only about databases: a metrics library, a JSON mapper and a distributed lock are infrastructure
  too, and each of them has a port and an adapter here rather than a dependency in the domain. What the domain
  says is *this build failed*, *only one instance may publish this site*; how that becomes a meter, a lock row
  or a file is decided by an adapter.

  Its `pom.xml` is the check that costs nothing: **a new dependency there needs a reason that is about the
  documentation domain**, not about how something is stored, measured, serialised or coordinated.
- **Serialisation formats belong to whoever reads them.** What the site template reads is written by
  `jeap-doc-sitegenerator`, because the format is a contract between the generator and the template, not a fact
  about documentation. And the formats of an upstream's payloads belong to the adapter of that upstream: a
  component's database schema and its OpenAPI specification are read in `jeap-doc-archrepo`, behind
  `ArchitectureArtifactContent`, and reach the generator as records of this service's own model. **A structure
  template may not hold a JSON mapper either** - the chapters and the rules are read from the web layer as
  well, and everything on a template's POM travels there with them.
- **Where Jackson is needed it is Jackson 3** - the `tools.jackson` group and packages, never
  `com.fasterxml.jackson`. Its exceptions are unchecked.
- An adapter module depends on the domain, never on another adapter.
- Each module contributes one auto-configuration (`Doc*Configuration`, registered in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`), so an instance provides its
  application class and its configuration and gets the wiring.
- New business logic goes into the domain; new technology goes into an adapter. A new adapter kind - an event
  publisher, an HTTP client for another service - becomes its own module.
- **A structure template is a plugin, and a module of its own.** The plugin point `StructureTemplate` is in the
  domain, because the upload validation in the web layer will read the chapters as much as the generator does,
  and that path must not reach the site generator. The structure template itself is in the module.

  A plugin point is not a driven port: a port has exactly one adapter, a plugin point has as many
  implementations as there are templates. **Nothing outside a template module names it** - the site generator
  injects every `StructureTemplate` it finds, and nothing names a template by class. A second structure template
  is a dependency and a bean. See [Structure templates](structure-templates.md).
- **An HTML parser lives in `jeap-doc-html` and nowhere else.** The domain stays free of infrastructure,
  `jeap-doc-markdown` has no dependencies, and `jeap-doc-objectstorage` is named for what it does - so the
  library that reads what a team's build produced is behind a port of its own, in a module that holds one
  dependency and one bean.
- **`jeap-doc-markdown` has no dependencies and must keep none.** It is reached from the templates and from the
  site generator, and through the templates it will be on the path of the upload validation - everything added
  to it travels all of that way. The moment it needs the domain it has stopped being a syntax helper.

## Receiving an upload

The path of an upload through the hexagon shows the layering at work: the web adapter binds and authorizes the
request, the domain decides what happens, and the two driven adapters do it.

1. `jeap-doc-web` binds the parameters, checks the semantic role and hands the body to the domain as a stream.
2. `jeap-doc-domain` records the upload through the repository port - **before** the bundle is read - stores the
   bundle through the storage port, and marks the upload as pending afterwards. No transaction is open while the
   bundle streams.
3. `jeap-doc-persistence` keeps the upload in `documentation_upload` and what it documents in
   `documentation_subject`, both with identifiers from a sequence; the identifier of an upload is what its bundle
   is stored under.
4. `jeap-doc-objectstorage` writes the bundle to the bucket, under the prefix of the incoming documentation.

What that means for a pipeline - the states, the retries and what is not checked - is described in
[Uploads](uploads.md).

## Generating and serving the documentation

The other half runs on its own: nothing calls it, and it calls nothing back.

**A site is published as several builds, one per part**, because one build of a whole landscape no longer fits -
see [Generating the documentation](generation.md). A part is a set of whole URL subtrees of the site: a part per
system, and a shell part for the site's own pages. Which parts a site has is one implementation of
`SitePartition` and nothing else knows the axis.

1. Something asks for a **part** to be published - an upload of that system's documents, the architecture import
   finding that system's model changed, a walk over the site, or an operator. All of them leave a **request**,
   at most one per part.
2. `jeap-doc-domain` takes the request under a lock named after the part, writes what that part contains into a
   workspace, and **hashes it**: content that is what is already published is not generated at all.
3. `jeap-doc-sitegenerator` installs the site template **over** that content and runs the generator as a child
   process. The technology - Docusaurus, Node - lives in this module and nowhere else.
4. `jeap-doc-objectstorage` writes the output under the identifier of the build - and the files every part
   emits identically under one prefix of the site - and **one row** in `documentation_build` then makes it what
   is served for that part.
5. `jeap-doc-web` serves the site to a browser: a path is answered out of the publication of the part that owns
   it, reading the rows and the objects.

**Generating and serving are separate, and neither is optional.** They share a database and a bucket and nothing
else: the generator's only output is objects plus a row, and the web server's only input is that row. That is
what keeps a reader's page fast while a build saturates a core, and what lets a build fail without a reader
noticing. What each side does is [Generating the documentation](generation.md).

## What the service provides

- the REST API with its security, its OpenAPI documentation and the endpoint receiving a documentation set,
- the object storage, whose bucket is checked at startup,
- the database connection, the transaction management and Flyway,
- the build, the OSS publication, the dependency updates and the vulnerability scan.

## Related

- [API](api.md)
- [Uploads](uploads.md)
- [Structure templates](structure-templates.md)
- [Generating the documentation](generation.md)
- [Configuration](configuration.md)
- [Security](security.md)
