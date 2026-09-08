# What an upload is validated against

Documentation reaches the doc service as a ZIP of a folder, and where each file sits in that folder is what
decides where it is published. This page is the **structural** half of what checks that: the rules the doc
service applies to the *paths* of an upload, per structure template.

**Two stages, and this is the second.** The content of the files - CommonMark, dead links, the front-matter
allowlist - is checked in the doc workflow, before anything is sent, and the doc service never sees a file's
bytes. The structure is checked here, against the template the upload names.

**Ask before you build the ZIP.** `POST /api/uploads/docs/validation` answers whether a path tree would be
accepted: `200` when there is nothing to report, `422` with a finding per problem when there is. Nothing is
uploaded, stored or read by it, and it has no side effect at all - see
[the API](api.md#validating-a-documentation-set). The upload endpoint does not apply these rules; what does,
apart from this endpoint, is the publication that writes an upload into the site.

## What is ignored, and never reported

A ZIP of a documentation folder carries files nobody wrote. They are dropped before any rule runs, and a
pipeline is never failed for them:

| Ignored | Why |
| ------- | --- |
| `.DS_Store`, `._<name>` | macOS Finder. The second is the sidecar carrying a file's resource fork |
| `__MACOSX/…` | The directory macOS `zip` adds beside the real one |
| `Thumbs.db`, `desktop.ini` | Windows Explorer |
| `.gitkeep`, `.gitignore` | Git placeholders in a folder that would otherwise not exist |
| `<name>~`, `<name>.swp`, `.~lock.<name>#` | Editor backups and lock files |

**A named list, not a pattern.** Deliberately *not* "ignore every hidden file": one that is not on this list
was written by somebody, and telling them about it beats dropping it in silence. The report says how many
paths were ignored, because a count that omits what it skipped is no better than a truncated list.

A set that holds nothing else is empty, and answered `EMPTY_TREE` - the workflow's `path` is pointing at a
folder with no documentation in it.

## What holds for every template

These rules are the doc service's, whatever template an upload names.

| # | Rule | Finding |
| - | ---- | ------- |
| 1 | The path is relative and normalized: no leading `/`, no `..`, no `.`, no empty segment, no backslash, no control character, no trailing `/`, at most 1024 characters | `INVALID_PATH` |
| 2 | No path appears twice | `DUPLICATE_PATH` |
| 3 | The path has at least two segments - a file at the root of the set belongs to no chapter, so nothing publishes it | `FILE_OUTSIDE_CHAPTER` |
| 4 | No folder inside a chapter: the pages of a chapter lie directly in it | `NESTED_FOLDER` |
| 5 | No file name begins with `.` or `_` - see below | `HIDDEN_NAME`, `UNPUBLISHABLE_NAME` |
| 6 | `index` is the chapter's own landing page, and the doc service writes it | `RESERVED_NAME` |
| 7 | The set holds at least one path that was not ignored | `EMPTY_TREE` |

Rules 3 to 6 are about a *chapter*, so they apply to a Markdown upload and not to an HTML one, which follows
no chapters at all.

### A name that will not be published

Two prefixes are refused, for two different reasons.

**`_` - because the site generator drops it.** Docusaurus excludes `_*.md` from a docs build, and the names it
keeps for itself - `_category_.json`, which is a chapter's navigation - are its own. Either way an upload
carrying one succeeds and publishes something other than the page that was meant. `UNPUBLISHABLE_NAME`.

**`.` - because a hidden file is not documentation.** The ones a tool writes are ignored by name above, so
anything still beginning with a dot was written deliberately, and a documentation set has no business
carrying it. `HIDDEN_NAME`.

### What is deliberately not checked

| | |
| --- | --- |
| Whether the system, component or library exists | It is created by the upload that names it |
| Whether a chapter is missing | Not an error: a repository documents what it documents, and no report mentions the chapters nobody wrote |
| Anything about a file's content | The workflow's half, and this endpoint never receives a byte of it |
| Whether the same tree was already uploaded | The endpoint has no memory and writes nothing |
| Case | A path is compared as it arrives and only its extension is folded, so `1-Intro/` is an unknown chapter. Saying so is more useful than accepting it into a case-sensitive object store |

## arc42

The template every jEAP repository uses today. Its rules are its own declarations - see
[Structure templates](structure-templates.md).

### The chapter folders

A Markdown upload's first path segment is one of these twelve, and nothing else:

```
1-intro                             7-deployment-view
2-constraints                       8-crosscutting-concepts
3-context-and-scope                 9-architecture-decision-records
4-solution-strategy                 10-quality-requirements
5-building-block-view               11-risks
6-runtime-view                      12-glossary
```

Anything else is `UNKNOWN_CHAPTER`, and where the folder is recognisably one of them written the wrong way -
`runtime-view`, `4-runtime-view`, `introduction` - the message names the one it means.

### The files it takes

```
md    png jpg jpeg gif webp avif svg
```

`mdx` is **not** among them and will not be: MDX is a programming language, and documentation the doc service
did not write itself is not trusted with one. Anything else is `FORBIDDEN_EXTENSION`.

**An image lies beside the page that shows it.** `5-building-block-view/overview.png` next to
`5-building-block-view/design.md`, referenced as `![Overview](overview.png)`. arc42 has no subfolders, so the
`images/` folder a repository reaches for by habit is a `NESTED_FOLDER` finding.

A diagram is better written as a fenced `plantuml`, `mermaid` or `dot` block than uploaded as a picture: the
site renders it in the reader's browser, so it stays diffable, searchable and legible in both themes. The
image formats are for the pictures that have no source - a screenshot, a photograph, a scan.

### What the generator writes, and an upload may not

A page of one of these names would produce two documents at one URL. The site is built with
`onDuplicateRoutes: 'throw'`, so it fails the build of that system's part - twenty minutes later, naming a
route rather than an upload, while the team's documentation stops updating. That is what `RESERVED_NAME`
prevents.

| Chapter | System docs | Component docs | Library docs |
| ------- | ----------- | -------------- | ------------ |
| `3-context-and-scope` | `system-context-view` | `context-view` | - |
| `5-building-block-view` | `whitebox-view`, `components`, `events`, `commands` | `database-schema`, `rest-api`, `messages` | - |
| `6-runtime-view` | `system-reactions` | `component-reactions` | - |

Plus `index` in every chapter, for every template.

Three of those are folders rather than pages - `components`, `events`, `commands` - and they are reserved for
the same reason: a folder with a landing page and a file of that name are one URL.

**Nothing is generated for a library**, so a library upload is bounded by the rules that hold for every
template and by the extensions above, and by nothing else.

**Only a `.md` can collide.** Only Markdown becomes a document, so
`5-building-block-view/whitebox-view.png` is an image that no page is served at and is accepted.

## An HTML upload follows no template

HTML is a **source format**, not a second template: an upload names `source-format=html` *and* a template,
because its `location` is a chapter of that template. What is published is the tree as it is, in an iframe, so
it follows none of the chapter rules and may nest as deeply as any built site.

| # | Rule | Finding |
| - | ---- | ------- |
| 1 | The path is relative and normalized, as above | `INVALID_PATH` |
| 2 | No path appears twice | `DUPLICATE_PATH` |
| 3 | The extension is one a built site is made of | `FORBIDDEN_EXTENSION` |
| 4 | `index.html` lies at the root of the set | `MISSING_ENTRY_POINT` |
| 5 | `location` names a chapter folder of the named template | `UNKNOWN_LOCATION` |

```
html htm css js map   json txt xml   png svg jpg jpeg gif webp avif ico
woff woff2 ttf otf    pdf webmanifest
```

`md` and `mdx` are refused here: uploaded Markdown belongs in a Markdown upload, where a template's rules
reach it. `_astro/`, `_next/` and `.well-known/` are accepted - a static export's own directories are its
business, and none of them goes through the docs build.

## The finding codes

What a pipeline branches on. Every finding names the path it is about, except the four that are about the set
as a whole.

| Code | What it means |
| ---- | ------------- |
| `INVALID_PATH` | Not a relative, normalized path, or longer than 1024 characters |
| `DUPLICATE_PATH` | The same path appears more than once |
| `FILE_OUTSIDE_CHAPTER` | A file at the root of the set, belonging to no chapter |
| `UNKNOWN_CHAPTER` | The first segment is not a chapter of the template |
| `NESTED_FOLDER` | A folder inside a chapter |
| `HIDDEN_NAME` | A file name beginning with a dot |
| `UNPUBLISHABLE_NAME` | A file name beginning with an underscore |
| `FORBIDDEN_EXTENSION` | An extension the template, or a microsite, does not take |
| `RESERVED_NAME` | A document of a name the doc service generates into that chapter |
| `UNKNOWN_TEMPLATE` | *Set-level.* No template of that name exists; the message names the ones that do |
| `EMPTY_TREE` | *Set-level.* Nothing in the set would be published |
| `MISSING_ENTRY_POINT` | *Set-level.* An HTML upload with no `index.html` at its root |
| `UNKNOWN_LOCATION` | *Set-level.* An HTML upload embedded at something that is not a chapter |

## Adding a template

A second structure template declares three things and writes no validation code at all:

| | |
| --- | --- |
| Its chapters | `chapters()`, which it already declares to generate into |
| The extensions an upload may carry | `allowedFileExtensions()` |
| What it generates into a chapter, if anything | `generatedNames(chapter, subject)`, which defaults to nothing |

Everything else - the path rules, the finding codes, the messages, the order and the cap - is the doc
service's, so the workflow that prints arc42's report prints the new template's unchanged. See
[Structure templates](structure-templates.md) for what a template is, and
[the API](api.md#validating-a-documentation-set) for the endpoint.

## Related

- [The API](api.md) - the endpoint, its parameters and its answers
- [Uploads](uploads.md) - how a documentation set reaches the doc service
- [Structure templates](structure-templates.md) - what a template is, and how to add one
- [Generating the documentation](generation.md) - what the doc service writes itself
