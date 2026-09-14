# Structure templates

A **structure template** says how documentation is organised: which chapters exist, where a page is served, and
what the doc service generates into those chapters from the architecture model. The service ships one,
[arc42](https://arc42.org). A further structure template is a further module.

Two very different things follow the same template. The documentation a team writes next to its code is filed
into its chapters, and the documentation the doc service generates is written into the same ones. That is what
makes one coherent site out of both.

## A template is a plugin

Architecturally a structure template is a **plugin**, and that is a third kind of thing beside the domain and the
adapters. It is not the centre of the hexagon, and it is not a technology behind a port.

`StructureTemplate` is the plugin point. It lives in the domain, in `ch.admin.bit.jeap.doc.domain.template`,
because two places read it that must not know about each other:

- `jeap-doc-sitegenerator` asks every template for the subtree of a system;
- the structure validation asks a template what it declares, and checks an upload's path tree against it -
  see [What an upload is validated against](upload-validation.md). **A template never sees a path**: it
  answers which folders are chapters, which extensions it takes and what it generates, and the deciding, the
  wording and the ordering of the findings are the domain's, so the codes are one set whatever methodology is
  named.

The upload path must not reach the site generator, so the type cannot live there. What lives in a template
module is the template itself.

It is a plugin point and not a driven port, and the difference matters for one rule: a port has exactly one
adapter, and a plugin point has as many implementations as there are templates.

**Nothing outside a template module names it.** The site generator injects every `StructureTemplate` bean it
finds and the web layer asks `StructureTemplates`; the arc42 module is on the classpath and no class refers to
it. Adding a second structure template is a dependency and a bean, and no change anywhere else.

```text
jeap-doc-domain            StructureTemplate, StructureChapter, GenerationContext, DocumentationPaths
       ▲                                    ▲
       │ implements                         │ injected as List<StructureTemplate>
jeap-doc-template-arc42            jeap-doc-sitegenerator, jeap-doc-web
```

## What a template module may depend on

A template module depends on `jeap-doc-domain` and `jeap-doc-markdown`, plus `spring-boot-starter` for its
auto-configuration. Anything else needs a reason that is about the structure template.

A template engine, an HTTP client, a JSON mapper or a dependency on `jeap-doc-sitegenerator` would be a leak.
The chapters and the rules are read from the web layer as well, and everything on that POM travels there with
them.

A template writes its pages through `jeap-doc-markdown`, which has no dependencies at all and must keep none.
Everything a page says goes through `MarkdownWriter`, so that escaping is done in one place rather than at every
call site.

## What the interface asks for

| Member                                        | What it is                                                                                                  |
|-----------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `id()`                                        | What an upload names in its `template` parameter                                                            |
| `systemPathSegment()` / `systemLabel()`       | The segment and the navigation label below a system                                                         |
| `componentPathSegment()` / `componentLabel()` | The same below a component                                                                                  |
| `libraryPathSegment()` / `libraryLabel()`     | And below a library, which no architecture model holds - every chapter of one is written by hand            |
| `chapters()`                                  | The chapters. Nothing else writes a chapter folder name, and the order they are declared in does not matter |
| `chapterOfFolder(folder)`                     | The chapter a folder belongs to, which is how an upload's first path segment is checked                     |
| `allowedFileExtensions()`                     | What an upload to this template may carry, lower case and without the dot. No default: an empty set would silently forbid everything |
| `generatedNames(chapter, subject)`            | The page and group names it writes into a chapter, for a system, a component or a library - the names an upload may not reuse. Defaults to nothing |
| `orderedChapters()`                           | The chapters in the order the navigation shows them - by number, or alphabetically. A default method        |
| `positionOf(chapter)`                         | Where a chapter goes among its siblings, which is the `position` of its `_category_.json`. A default method |
| `writeSystem(system, context, directory)`     | Writes the pages of one system. A template with nothing to say writes nothing                               |

A template is named for what it describes, so the same structure reads as *System Architecture* below a system
and as *Component Architecture* below a component.

### What `writeSystem` is handed

**One entry point, and the subject says which case it is in.** `SystemDocumentation` carries the architecture
model's system where there is one, everything uploaded for that system's subtree, and a writer for the
uploaded pages. So a template branches once, at the top, rather than checking for a missing model on every
page:

| It asks | And gets |
|---|---|
| `model()` | The `DocumentedSystem`, or empty - a system can be documented before anything is deployed |
| `components()` | Every component to document: the model's, and those only an upload knows |
| `libraries()` | The libraries of the system, which no model ever holds |
| `customChaptersOfTheSystem()`, `customChaptersOfComponent(slug)` | Which chapter folders carry uploaded pages |
| `pages().writeInto(subject, chapter, directory)` | Writes those pages into a directory the template made |

**A template creates the chapter folder and the writer fills it.** Only a template may name a chapter - the
label and the position come from its `StructureChapter` - and the front matter and the ordering of an
uploaded page are one implementation for every methodology, so they are not the template's to decide. A
template calls the writer while it walks its own chapters, which keeps it one pass.

**A template never sees an uploaded page's bytes.** It is told which chapters carry pages, not what they say.

## Numbered chapters, or not

arc42 numbers its chapters and the numbers are part of the method, so they belong in the folder, in the label
and in the order. **A methodology that does not number its chapters is just as welcome**, and says so by
building its chapters with the other factory:

```java
StructureChapter.numbered(5, "5-building-block-view", "Building Block View")
StructureChapter.unnumbered("decisions", "Decisions")
```

|                                                                  | Numbered                                                                              | Unnumbered                                   |
|------------------------------------------------------------------|---------------------------------------------------------------------------------------|----------------------------------------------|
| The folder, which an upload carries and which is written on disk | `5-building-block-view`                                                               | `decisions`                                  |
| The URL segment                                                  | `building-block-view` - the folder without the prefix, so links survive a renumbering | `decisions` - the folder itself              |
| The label in the navigation                                      | `5. Building Block View`                                                              | `Decisions`                                  |
| The order                                                        | the number, **gaps kept**: a reader of arc42 sees that chapter 7 has not been written | **the title, alphabetically, ignoring case** |
| The `position` of `_category_.json`                              | the number                                                                            | the place in that alphabet, counted from 1   |

Two rules go with it, both checked while the service starts, because a template is a module on the classpath
and a mistake in one belongs in a deployment's log rather than in a build twenty minutes later:

- **A template numbers every chapter or none of them.** Half a numbering is not an order.
- **No two chapters may share a folder or a URL segment.** `5-glossary` and `glossary` are one page, and the
  second would be written over the first while the navigation still named both - which nothing downstream
  notices.

And one rule on the folder of an unnumbered chapter: **it may not begin with a digit.** Docusaurus strips a
leading number from a folder by itself - that is what makes the numbered chapters work - so a folder called
`2024-decisions` would be served at `decisions`, where nothing links to it, and no build would fail over it.

**The order is this service's, not the site generator's.** Docusaurus does sort the items of a folder it has no
position for, but by what it sorts them is its business; `positionOf` puts an explicit position into every
category file, so the navigation reads the same whichever version of it is installed.

## arc42

`jeap-doc-template-arc42` implements the twelve arc42 chapters.

| Folder                            | Chapter                  |
|-----------------------------------|--------------------------|
| `1-intro`                         | Introduction and Goals   |
| `2-constraints`                   | Architecture Constraints |
| `3-context-and-scope`             | Context and Scope        |
| `4-solution-strategy`             | Solution Strategy        |
| `5-building-block-view`           | Building Block View      |
| `6-runtime-view`                  | Runtime View             |
| `7-deployment-view`               | Deployment View          |
| `8-crosscutting-concepts`         | Cross-cutting Concepts   |
| `9-architecture-decision-records` | Architecture Decisions   |
| `10-quality-requirements`         | Quality Requirements     |
| `11-risks`                        | Risks and Technical Debt |
| `12-glossary`                     | Glossary                 |

The generator writes into four of them. The other eight are there for what a team uploads.

**A component carries the same twelve chapters one level down**, under a segment of its own, and the generator
writes into the same four of them. Which of a component's chapters exist depends on what the architecture
repository knows about it: chapters 1, 3 and 6 can always be written, and chapter 5 appears when there is a
database schema, a REST API or a message contract to put in it.

### Where a page is served

```text
/systems/                                                                       every system, with its team
/systems/orders/                                                                what the system is, and its structures
/systems/orders/system-architecture/                                            arc42 for the system
/systems/orders/system-architecture/intro/                                      1. Introduction and Goals
/systems/orders/system-architecture/context-and-scope/                          3. Context and Scope
/systems/orders/system-architecture/context-and-scope/system-context-view/
/systems/orders/system-architecture/building-block-view/                        5. Building Block View
/systems/orders/system-architecture/building-block-view/whitebox-view/
/systems/orders/system-architecture/building-block-view/components/orders-foo-bar-service/
/systems/orders/system-architecture/building-block-view/events/orders-payment-accepted-event/
/systems/orders/system-architecture/building-block-view/commands/orders-check-availability-command/
/systems/orders/system-architecture/runtime-view/                               6. Runtime View
```

And below one component, `orders-foo-bar-service` above:

```text
.../components/orders-foo-bar-service/                                          the component, in the system's tree
.../components/orders-foo-bar-service/component-architecture/                   arc42 for the component
.../component-architecture/intro/                                               1. Introduction and Goals
.../component-architecture/context-and-scope/                                   3. Context and Scope
.../component-architecture/context-and-scope/context-view/                      the component context view
.../component-architecture/building-block-view/                                 5. Building Block View
.../component-architecture/building-block-view/database-schema/                 the entity relationship diagram
.../component-architecture/building-block-view/rest-api/                        the API by group, and the Swagger link
.../component-architecture/building-block-view/messages/                        what it produces and consumes
.../component-architecture/runtime-view/                                        6. Runtime View
```

The component's context view is served at `context-view` and not at `component-context-view`: the path already
carries the component and `component-architecture`, so the prefix would say the word a third time. The heading
and the navigation label are *Component Context View* all the same.

And below one library, which stands in the same chapter as the components:

```text
.../building-block-view/libraries/                     every library of the system
.../building-block-view/libraries/orders-common-lib/   the library
.../libraries/orders-common-lib/library-architecture/  arc42 for the library
.../library-architecture/intro/                        1. Introduction and Goals
.../library-architecture/intro/library-overview/       what the upload said about it
```

A library's twelve chapters are written by hand - no architecture model holds one - so everything below
`library-architecture/` except the overview page comes from an upload. See
[The documentation a team writes](custom-documentation.md).

### Where a microsite is served

An HTML upload is not written into the tree at all. It is published under `/microsites/` and the generator
writes **one page** into the chapter the upload named, whose route is the topic under a namespace of its own:

```
systems/<system>/system-architecture/<chapter>/microsites/<topic>/     the page with the frame
/microsites/<system>/<template>/<location>/<topic>/                    the microsite's own files
```

The namespace is what keeps a microsite from ever taking the route of an uploaded page beside it, and the
page is ordered among that chapter's pages by its label. See
[The documentation a team writes](custom-documentation.md).

### Three rules, and an upload has to keep them too

- **The chapter folder carries its arc42 number, the URL does not.** A chapter is the folder
  `5-building-block-view`, is served at `/building-block-view/`, and reads as *5. Building Block View* in the
  navigation. Links then survive a renumbering. A relative Markdown link between two pages of a repository still
  resolves once they are published. A template that does not number its chapters has the folder and the URL
  segment be the same thing - see [above](#numbered-chapters-or-not).
- **A chapter with nothing in it does not exist.** The generator creates the four it has something to say about.
  A gap in the numbering means a chapter has not been written, not that it is empty.
- **A component lives inside the building block view.** A component is one of the blocks, so its documentation
  sits where the decomposition is described, next to the events and commands that flow between them. Its own
  chapters hang **below** its page rather than beside it, so a reader who followed a link into the subtree is
  still inside the component.

### Diagrams

Diagrams are fenced PlantUML source, never images. The site's plugin renders them in the reader's browser, so a
diagram stays searchable and readable as text.

A fence is the one place the Markdown escaping cannot help, because nothing inside it is Markdown. Names that
come from the architecture model are escaped for PlantUML instead, and a box only links to a page when the model
says that page exists.

**Every diagram is bounded, and every reduction says so.** The diagram engine lays a diagram out by
recursion, so an unbounded one is not a large picture but no picture at all in the reader's browser. A diagram
that had to leave something out says how much, and the table below it carries what it left out - see
[the generator's properties](configuration.md#the-architecture-model).

The database schema page is the one exception, and it is measured rather than a matter of taste: a component
that publishes 6583 tables gave it 33 527 rows of columns and an hour and a half of build time, so its list is
bounded too. Where that applies the page says how many entries it did not write - and it **links nothing**: the
only source carrying the rest is the architecture repository's own `/docs-api`, an internal address a reader of
a published site cannot reach, so a link there would read as an offer and answer nothing.

| Diagram                       | Bounded by                                | The page still carries      |
|-------------------------------|-------------------------------------------|-----------------------------|
| System context view           | `max-diagram-nodes` other systems         | every relation              |
| Whitebox view                 | `max-diagram-nodes` other systems         | every component, every relation |
| Component context view        | `max-context-components` component boxes - siblings and foreign counterparts together - and `max-diagram-nodes` other systems | every relation |
| Entity relationship diagram   | `max-schema-table-diagram` entries               | `max-schema-table-list` entries, with their columns |
| Any arrow of any of them      | `max-edge-labels` names, then their count | the names, in the table     |

## Adding a template

1. A new module, `jeap-doc-template-<name>`, depending on `jeap-doc-domain` and `jeap-doc-markdown`.
2. An implementation of `StructureTemplate` with a distinct `id()`, and its chapters - all
   `StructureChapter.numbered` or all `StructureChapter.unnumbered`. Write `positionOf(chapter)` into each
   chapter's `_category_.json` rather than a number of your own.
3. An auto-configuration contributing it as a bean, registered in
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
4. The module on the instance's classpath.

Nothing else changes. The site generator picks it up, and uploads may name its id.

**And it writes no validation code.** Declaring `chapters()`, `allowedFileExtensions()` and
`generatedNames(chapter, subject)` is the whole of it: the path rules, the finding codes, the messages, the
order and the cap are the doc service's, so the workflow that prints arc42's report prints the new template's
unchanged - see [What an upload is validated against](upload-validation.md#adding-a-template).

## Related

- [Architecture](architecture.md) - where a plugin sits among the modules
- [Generating the documentation](generation.md) - what a build does with a template
- [API](api.md) - the `template` parameter of an upload
- [Uploads](uploads.md) - what an upload has to look like
- [The documentation a team writes](custom-documentation.md) - what a template is handed about the uploaded pages
- [What an upload is validated against](upload-validation.md) - the rules a template's declarations become
