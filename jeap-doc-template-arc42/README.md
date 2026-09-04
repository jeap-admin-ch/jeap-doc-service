# jeap-doc-template-arc42

The [arc42](https://arc42.org) structure template of the jEAP Doc Service: the twelve chapters, the rules an
upload has to follow, and the pages the doc service generates into them from the architecture model.

## What it is

A **structure template** module. It implements `StructureTemplate` from `jeap-doc-domain`, and nothing outside
it names it. The site generator injects every template it finds, and the web layer asks the registry. A second
methodology is a second module.

Two `jeap-doc` dependencies, plus `spring-boot-starter` for the auto-configuration. Anything else needs a reason
that is about arc42:

|                     |                                                                                              |
|---------------------|----------------------------------------------------------------------------------------------|
| `jeap-doc-domain`   | The architecture model, the template type, and the URL layout of the documentation           |
| `jeap-doc-markdown` | How a page is written - and where the escaping lives, so that no template has to remember it |

A template engine, an HTTP client, a JSON mapper or a dependency on `jeap-doc-sitegenerator` would be a leak.
The chapters and the rules are read from the web layer too, and everything on this POM travels there with
them.

## What it generates

Four of the twelve chapters. The other eight are not created at all: an empty chapter claims there is content
when there is none, and a gap in the numbering tells a reader it has not been written.

| Chapter                   | Pages                                                                                     |
|---------------------------|-------------------------------------------------------------------------------------------|
| 1. Introduction and Goals | What the system is and who is responsible for it                                          |
| 3. Context and Scope      | The system context view, as a PlantUML diagram and a table of neighbours                  |
| 5. Building Block View    | The level-1 whitebox view, one page per component, one page per event and per command     |
| 6. Runtime View           | System Reactions, waiting for the reaction observer import                                |

Diagrams are fenced PlantUML source, never images. The site's plugin renders them in the reader's browser, so a
diagram stays searchable and readable as text.

Three facts about the diagrams belong to this template, because they are how it draws rather than what the
model says:

- **The whitebox page carries two of them.** *Inside the system* draws the components and what flows between
  them and nothing else; *With the neighbouring systems* adds every other system as a single box. The first is
  the only one a large system can be read from, and it is written only when the components exchange something -
  otherwise it would be the same boxes twice.
- **The direction follows the shape of the view, not its size.** The context view is a star of two ranks and is
  laid out `left to right`; a whitebox view is a graph of components calling components and is laid out top to
  bottom. Measured over the real landscape, the number of boxes predicts neither.
- **An arrow shows at most `jeap.doc.generator.max-edge-labels` names**, and the count for its kind above that -
  `5 Events`. The engine lays a label out by recursion and overflows the browser's stack at about sixty lines,
  so this is what makes a busy system's diagram render at all. Every name is in the page's table.

## Licence

arc42 is by **Gernot Starke and Peter Hruschka** ([arc42.org](https://arc42.org)) and is licensed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). This module uses the section names in English,
addresses the sections by number-prefixed folder names, and implements the section structure rather than
shipping arc42's own explanatory text. See [`NOTICE`](../NOTICE) in the root of this repository.

The generated site carries the attribution once, at the foot of chapter 1 of every system - not on every page.
