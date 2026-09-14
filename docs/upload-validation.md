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
[the API](api.md#validating-a-documentation-set).

**The endpoint is advisory; the upload is not.** A pipeline may skip the endpoint, and a hand-made ZIP is
refused all the same: the upload applies these rules to the paths of the archive it received, from the same
code, and answers `422` with the same findings. So this endpoint is what keeps a misfiled page out of a
*build log* rather than out of a site - the site is kept clean by the upload.

**And a build applies them once more**, to a set that is already stored. It is the backstop for a set that
entered before a rule existed, or for a page whose name a template only started generating later: such a page
is left out with a line in the build log, and never fails the build.

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
| 4 | A page lies directly in its chapter. An asset may lie in a folder inside the chapter, at most five folders below it | `NESTED_FOLDER` |
| 5 | No file or folder name begins with `.` or `_` - see below | `HIDDEN_NAME`, `UNPUBLISHABLE_NAME` |
| 6 | No file name is one the site generator reads as the chapter's landing page - `index`, `readme` or the chapter folder's own name, in any case - because the doc service writes that page. No folder right below the chapter carries a name the doc service writes there | `RESERVED_NAME` |
| 7 | No two documents of one chapter carry the same name once a leading number is taken off it - see below - and no file has the path of a folder another file of the set lies in | `COLLIDING_NAME` |
| 8 | The set holds at least one path that was not ignored | `EMPTY_TREE` |

Rules 3 to 7 are about a *chapter*, so they apply to a Markdown upload and not to an HTML one, which follows
no chapters at all.

### A number in front of a name is not part of the name

The site generator parses a **leading number** off a document's file name and publishes what is left: `01-rest-api.md`
is the document `rest-api`, at the URL `rest-api`. Two consequences, and both are checked:

- a numbered name **can be a reserved one**. `01-rest-api.md` in `5-building-block-view/` is the page the doc
  service generates there, and `01-index.md` is the chapter's generated landing page - both are `RESERVED_NAME`.
- two names that **differ only in their number** are one document. `foo.md` beside `1-foo.md`, or `1-foo.md`
  beside `2-foo.md`, are two files of the set and one page of the site, which fails the build of that part;
  both files are reported, because which of them to rename is the author's choice.

What looks like a date or a version is left alone, exactly as the generator leaves it alone: `2021-11-notes.md`
and `7.0-notes.md` are pages of their own name. The one rule that reads the name **as written** is the
landing-page rule above, because that is the name the generator asks it of: `1-intro.md` in `1-intro/` is that
chapter's landing page, while `intro.md` in the same folder is an ordinary page at a URL of its own.

### A name that will not be published

Two prefixes are refused, for two different reasons. They apply to the name of a file and to the name of every
folder an asset lies in.

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
| Case | A path is compared as it arrives, so `1-Intro/` is an unknown chapter. Saying so is more useful than accepting it into a case-sensitive object store. The exceptions are the extension and the landing page names of rule 6, which the site generator folds itself |

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
`runtime-view`, `4-runtime-view`, `introduction` - the message names the one it means. **The name decides
before the number does**: `4-runtime-view` is answered with `6-runtime-view`, the chapter it names, and not
with chapter 4. A number is what the guess falls back on when the name matches nothing, which is the case of a
folder written in another language.

### The files it takes

```
md    png jpg jpeg gif webp avif svg    pdf txt csv json yaml yml
```

`mdx` is **not** among them and will not be: MDX is a programming language, and documentation the doc service
did not write itself is not trusted with one. Anything else is `FORBIDDEN_EXTENSION`, unless the instance adds
it with `jeap.doc.custom.additional-asset-extensions` - see [Configuration](configuration.md).

**An image lies beside the page that shows it, or in a folder inside the chapter.**
`5-building-block-view/overview.png` next to `5-building-block-view/design.md` is referenced as
`![Overview](overview.png)`, and `5-building-block-view/img/overview.png` as `![Overview](img/overview.png)`.
A folder holds assets only, and at most five folders deep: a page in a folder is a `NESTED_FOLDER` finding,
because a folder of pages would become a section of the navigation that the template does not have.

A folder is a name like a file's. **The folder right below the chapter may not carry a name the doc service
writes into that chapter** - `5-building-block-view/components/overview.png` in a system's set would put the
picture into the generated tree of its components - and that is `RESERVED_NAME`. **And one path is not both a
file and a folder**: `1-intro/overview.png` beside `1-intro/overview.png/small.png` is `COLLIDING_NAME` on the
file, since no file system writes both.

A diagram is better written as a fenced `plantuml`, `mermaid` or `dot` block than uploaded as a picture: the
site renders it in the reader's browser, so it stays diffable, searchable and legible in both themes. The
image formats are for the pictures that have no source - a screenshot, a photograph, a scan.

The other files are ones a page links to rather than shows - a specification, sample data, an example payload or
configuration: `[The API specification](files/api-spec.pdf)`.

### What the generator writes, and an upload may not

A page of one of these names would produce two documents at one URL. The site is built with
`onDuplicateRoutes: 'throw'`, so it fails the build of that system's part - twenty minutes later, naming a
route rather than an upload, while the team's documentation stops updating. That is what `RESERVED_NAME`
prevents.

| Chapter | System docs | Component docs | Library docs |
| ------- | ----------- | -------------- | ------------ |
| `1-intro` | `not-in-the-architecture-model` | `not-in-the-architecture-model` | `library-overview` |
| `3-context-and-scope` | `system-context-view` | `context-view` | - |
| `5-building-block-view` | `whitebox-view`, `components`, `libraries`, `events`, `commands` | `database-schema`, `rest-api`, `messages` | - |
| `6-runtime-view` | `system-reactions` | `component-reactions` | - |

Four of those are folders rather than pages - `components`, `libraries`, `events`, `commands` - and they are
reserved for the same reason: a folder with a landing page and a file of that name are one URL.

**`not-in-the-architecture-model` is reserved whether or not the model holds the subject.** It is the page a
system or component gets when nothing is deployed yet and the architecture model therefore knows nothing
about it. Reserving it only while the model is silent would make one and the same upload valid on one day and
invalid on the next, decided by an import rather than by anything a team did.

Plus, in every chapter of every template, the names the site generator reads as that chapter's landing page:
`index`, `readme` and the chapter folder's own name - `1-intro/1-intro.md`. Those three are folded, because the
generator folds them: `README.md` and `INDEX.MD` are the same page to it.

**A library gets one generated page**, `1-intro/library-overview.md`: the version, the repository, the branch
and the commit its upload named. Nothing else about a library is generated - no architecture model holds one -
so all twelve of its chapters are the team's to write, and its upload is bounded by the rules that hold for
every template, the extensions above, and that one name.

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
| 3 | The extension is not one a workstation runs | `FORBIDDEN_EXTENSION` |
| 4 | `index.html` lies at the root of the set | `MISSING_ENTRY_POINT` |
| 5 | `location` names a chapter folder of the named template | `UNKNOWN_LOCATION` |
| 6 | No `_jeap-search.tsv` at the root of the set | `RESERVED_PATH` |

```
exe com cmd bat msi scr lnk reg   ps1 psm1 vbs vbe wsf   sh bash zsh
jar dll so dylib                  app deb rpm apk pkg dmg
```

**A microsite follows no allowlist**, because it follows no template: it is published as it is, and a build
emits file types nobody listed in advance - a source map, a web manifest, a `LICENSE` with no extension at
all, and the Markdown or CSV a generated report links to. What is refused is what a workstation runs, and an
instance may set its own list with
[`jeap.doc.custom.refused-extensions`](configuration.md#the-documentation-a-team-writes). `_astro/`, `_next/`
and `.well-known/` are accepted - a static export's own directories are its business, and none of them goes
through the docs build.

What bounds what a microsite may *do* is not this list: it is served with an opaque origin and framed in a
sandbox - see [Security](security.md).

**One name is the service's own.** `_jeap-search.tsv` at the root of a set is where the text of the
microsite's pages is stored when it is uploaded, under the same prefix as its files so that removing the set
removes it too - so a set carrying that path is refused rather than silently overwritten. Deeper in the tree
the name is a team's own business.

## The finding codes

What a pipeline branches on. Every finding names the path it is about, except the four that are about the set
as a whole.

| Code | What it means |
| ---- | ------------- |
| `INVALID_PATH` | Not a relative, normalized path, or longer than 1024 characters |
| `RESERVED_PATH` | A path the doc service writes itself, which a set may not bring |
| `DUPLICATE_PATH` | The same path appears more than once |
| `FILE_OUTSIDE_CHAPTER` | A file at the root of the set, belonging to no chapter |
| `UNKNOWN_CHAPTER` | The first segment is not a chapter of the template |
| `NESTED_FOLDER` | A page in a folder inside a chapter, or an asset more than five folders below it |
| `HIDDEN_NAME` | A file or folder name beginning with a dot |
| `UNPUBLISHABLE_NAME` | A file or folder name beginning with an underscore |
| `FORBIDDEN_EXTENSION` | An extension the template does not take, or one a microsite refuses |
| `RESERVED_NAME` | A document of a name the doc service generates into that chapter, or one the site generator reads as the chapter's landing page - a leading number is taken off the name first. Or a folder right below the chapter of such a name |
| `COLLIDING_NAME` | Two documents of one chapter that the site generator would publish at one URL, because a leading number is not part of a page's name. Or a file at the path of a folder another file of the set lies in |
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
- [The documentation a team writes](custom-documentation.md) - what becomes of a set that passes these rules
- [Structure templates](structure-templates.md) - what a template is, and how to add one
- [Generating the documentation](generation.md) - what the doc service writes itself
