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
- `jeap-doc-web` will validate an upload against the chapters of the template it names. That validation is not
  written yet: an upload's `template` parameter is checked for being a slug and nothing more.

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
| `chapters()`                                  | The chapters. Nothing else writes a chapter folder name, and the order they are declared in does not matter |
| `chapterOfFolder(folder)`                     | The chapter a folder belongs to, for validating an upload                                                   |
| `orderedChapters()`                           | The chapters in the order the navigation shows them - by number, or alphabetically. A default method        |
| `positionOf(chapter)`                         | Where a chapter goes among its siblings, which is the `position` of its `_category_.json`. A default method |
| `writeSystem(system, context, directory)`     | Writes the pages of one system. A template with nothing to say writes nothing                               |

A template is named for what it describes, so the same structure reads as *System Architecture* below a system
and as *Component Architecture* below a component.

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

### Three rules, and an upload has to keep them too

- **The chapter folder carries its arc42 number, the URL does not.** A chapter is the folder
  `5-building-block-view`, is served at `/building-block-view/`, and reads as *5. Building Block View* in the
  navigation. Links then survive a renumbering. A relative Markdown link between two pages of a repository still
  resolves once they are published. A template that does not number its chapters has the folder and the URL
  segment be the same thing - see [above](#numbered-chapters-or-not).
- **A chapter with nothing in it does not exist.** The generator creates the four it has something to say about.
  A gap in the numbering means a chapter has not been written, not that it is empty.
- **A component lives inside the building block view.** A component is one of the blocks, so its documentation
  sits where the decomposition is described, next to the events and commands that flow between them.

### Diagrams

Diagrams are fenced PlantUML source, never images. The site's plugin renders them in the reader's browser, so a
diagram stays searchable and readable as text.

A fence is the one place the Markdown escaping cannot help, because nothing inside it is Markdown. Names that
come from the architecture model are escaped for PlantUML instead, and a box only links to a page when the model
says that page exists.

## Adding a template

1. A new module, `jeap-doc-template-<name>`, depending on `jeap-doc-domain` and `jeap-doc-markdown`.
2. An implementation of `StructureTemplate` with a distinct `id()`, and its chapters - all
   `StructureChapter.numbered` or all `StructureChapter.unnumbered`. Write `positionOf(chapter)` into each
   chapter's `_category_.json` rather than a number of your own.
3. An auto-configuration contributing it as a bean, registered in
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
4. The module on the instance's classpath.

Nothing else changes. The site generator picks it up, and uploads may name its id.

## Related

- [Architecture](architecture.md) - where a plugin sits among the modules
- [Generating the documentation](generation.md) - what a build does with a template
- [API](api.md) - the `template` parameter of an upload
- [Uploads](uploads.md) - what an upload has to look like
