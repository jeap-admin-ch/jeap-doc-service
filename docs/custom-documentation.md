# The documentation a team writes

The doc service generates part of a system's documentation from the architecture model. The rest is written by
the team that owns it, next to its code, and uploaded. This page is about that half: where a page lands, what
the service adds to it, and what happens to a subject the architecture model has never heard of.

The rules a set is checked against are on [What an upload is validated against](upload-validation.md), and how
a bundle reaches the service is on [Uploads](uploads.md).

## A set, and what identifies it

Everything uploaded in one request is a **documentation set**: the current documentation of one subject, in one
format, following one structure template. A further upload of the same set replaces it **whole**, so a page a
team deleted stops being served without anything having to notice it went.

What identifies a set - and therefore what an upload replaces:

|                        |                                                |
| ---------------------- | ---------------------------------------------- |
| the site               | `site`, or the default one                     |
| what it documents      | `type`, `system`, and `component` or `library` |
| the format             | `source-format`                                |
| the structure template | `template`                                     |
| for an HTML microsite  | `location` and `topic`                         |

Everything else an upload carries is a property of the set rather than part of its identity. A new `version`
replaces the documentation; that is what *current* means.

**Two things follow from `template` being part of the key.** A subject may carry two methodologies at once -
and a team that switches from one to the other leaves the old set behind, because the new upload replaces
nothing. Removing it is [an explicit call](#removing-documentation).

## Where a page lands

The folder inside the ZIP is the chapter. Nothing else decides:

```
docs/                                     https://…/systems/catalog/system-architecture/
├── 1-intro/
│   └── goals.md            ────────────▶     intro/goals
├── 2-constraints/
│   └── given.md            ────────────▶     constraints/given
└── 12-glossary/
    └── terms.md            ────────────▶     glossary/terms
```

The number prefix is in the folder and not in the URL: chapter numbers are part of arc42, and a link should
survive a methodology that numbers its chapters differently.

- **System documentation** lands under the system, **component documentation** under the component, and
  **library documentation** under the library - see [Structure templates](structure-templates.md) for where
  each of those is served.
- **A chapter the service does not generate** gets its folder from the structure template, with the label and
  the place in the navigation the template gives it, and a landing page so that a reader who opens the chapter
  lands on something.
- **A chapter it does generate** takes the uploaded pages beside the generated ones. The generator owns a
  chapter's `index` page and an upload owns the named pages next to it, which is what makes *a page is
  generated or custom, never both* something the service can check rather than something everyone agrees to.
- **Every environment tree** of the site gets the same pages. An upload names no environment, and what a team
  writes is about the thing rather than about a stage.

**A page may fold part of itself.** The site renders a `details` directive as a collapsible, closed until the
reader opens it:

```markdown
:::details[Every property]

The folded Markdown, a table or a diagram included.

:::
```

It is a directive and not a `<details>` tag, because raw HTML in a page is shown as text. The summary is text
as well.

**A table can be sorted and filtered in the browser.** Every Markdown table of a page gets a sort button in each
header cell, and one with more than 15 rows a filter above it - an uploaded table as much as a generated one, with
nothing to write for it. A table needs a header row to be sortable - see
[Every table can be sorted, and a long one filtered](generation.md#every-table-can-be-sorted-and-a-long-one-filtered).

## What the service adds to a page

**The body is written through unchanged.** Nothing is appended to it and nothing inside it is rewritten - an
admonition added after an unterminated code fence would be a broken page, and a team's Markdown is not the
service's to edit. The blank lines between the front matter and the first line are normalised, and that is
the whole of it. A page that is not UTF-8 text is left out with a line in the build log rather than repaired:
replacing the bytes the service cannot read would publish a page nobody wrote.

**The front matter is generated.** The keys an upload may carry are kept as they were written, and the ones
the service decides are added:

| Key                                                                                                              | What it is                                                                                                     |
| ---------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| `title`, `description`, `sidebar_label`, `tags`, `keywords`                                                      | Kept from the upload, exactly as written                                                                       |
| `sidebar_position`                                                                                               | Assigned by the service - from the sorted titles, or from a position or a reference the page named - see below |
| `doc_status`                                                                                                     | `custom`, against the `generated` of a page the service wrote                                                  |
| `doc_source`, `doc_source_repository`, `doc_source_ref`, `doc_source_revision`, `doc_version`, `doc_uploaded_at` | Where the page came from                                                                                       |

On a static site's page `sidebar_position` is kept exactly as uploaded instead - a static site has none of
the siblings the service would otherwise place it around, so Docusaurus orders its pages itself. `sidebar_after`
and `sidebar_before` are never kept: they only ever place a page relative to a chapter's own items, which is
read once from the raw upload and never published.

**It is parsed as YAML and written as YAML**, which is what Docusaurus will do with it. So a quoted scalar
running over several lines is the value it says it is, a title holding a colon comes back quoted, and a
version or an instant is published as text rather than as a number or a date. A page whose block cannot be
read at all - it is not a mapping, it names a type, it is an expansion bomb - is a page with no front matter
of its own, published with what this service adds and nothing else.

Anything else is dropped. It is an allowlist rather than a blocklist on purpose: a page cannot claim to be
generated, or take over a route with `slug`, by carrying the key itself.

**The provenance is rendered from those keys**, by the site template, under every page - a generated page and
an uploaded one alike. It is the one place that decides what provenance looks like, and it is the only way an
uploaded page can carry any: nothing may be appended to its body.

## An HTML microsite

A build that produces HTML - a generated API reference, an Allure report, Spring REST Docs output - uploads
it with `source-format=html`. Such a set is **not** written into the site: it is published as it was built,
served file by file under `/microsites/`, and the generator writes **one page** into the chapter the upload
names, with the microsite in a frame and the navigation and sidebar of the site around it.

| | |
| --- | --- |
| Where it goes | The `location` of the upload is the chapter; the `topic` is the last segment of its route, under `microsites/` within that chapter |
| What names it | The `label` of the upload - a microsite has no pages to take a title from, so this is the only thing that can name it in the navigation |
| Its own URL | `/microsites/<system>/components/<name>/<template>/<location>/<topic>/` - a component's and a library's name their kind, a system's own does not. This is the URL the frame opens, and the one to link to |
| Where it is ordered | Among the uploaded pages of its chapter, by its label by default - the two are one list. A microsite has no front matter of its own, so `sidebar-position`, `sidebar-after` and `sidebar-before` are upload parameters instead, read into the same hint a page's front matter would give |
| Whether it is searchable | **Yes, its content and not only its label.** The text of its pages is taken out of the bundle when it is uploaded and stored beside its files, and a hit opens the page that frames it at the file that matched. See [Search](search.md) |
| One reserved name | `_jeap-search.tsv` at the root of the set is where that text is stored, so an upload carrying that path is refused. Anywhere deeper in the tree the name is yours |
| What is searched of it | The first **200** `.html` and `.htm` files, the entry point first, and at most 512 KB of each. A set with more pages is published whole and searched down to the cap - it is what keeps one microsite of several hundred pages from being the whole result list |

### What the sandbox takes away, and the one script that gives some of it back

A microsite is served with an **opaque origin**: it cannot read the documentation site around it, cannot
reach its storage or cookies, and cannot navigate the page that frames it. That is the point - it is code
this service did not write - but it also means an application that reads `localStorage` while it loads never
starts.

> **The doc service therefore adds one `<script>` to every HTML page of a microsite it serves.** It is
> injected after the opening `<head>` tag, before the page's own scripts, and it gives the document an
> in-memory `localStorage`, `sessionStorage` and cookie jar that live as long as the page is open. Nothing is
> persisted and the origin stays opaque. **Everything after that tag is byte for byte what was uploaded**, and
> a page that already carries the tag is left alone.

A microsite may also tell its page how tall it is, and the page will grow the frame to fit - up to four
screens. It is opt-in and a microsite that says nothing is unaffected:

```js
parent.postMessage({type: 'jeap-doc-microsite-height', height: document.body.scrollHeight}, '*');
```

Everything that is not an HTML page is served with `Content-Disposition: attachment`, so a file a browser
would render as a document is downloaded instead. A microsite's own stylesheets and scripts are unaffected -
a browser ignores that header for a subresource.

## The order of the pages in a chapter

**By default, the pages of a chapter are sorted by their titles**, after the ones the service generates into
it. A page with no title is sorted by its file name, which is what Docusaurus falls back to for the heading
anyway, and the file name breaks a tie between two pages with the same title - so the order never depends on
how the archive happened to list them.

**A page may ask for a different place**, in its own front matter:

- `sidebar_position` ranks it among the items that name no reference of their own - lower first;
- `sidebar_after` or `sidebar_before` places it directly next to one other item of the same chapter, by
  name - a generated page included, e.g. `sidebar_after: system-context-view`. At most one of the two.

A page naming neither is free, and sorts by title exactly as before. The service still assigns every page's
final `sidebar_position` itself, past whatever the template generates into the chapter: Docusaurus breaks a
tie between two equal positions by file name, which is the one thing assigning a position is meant to take
out of it, and a declared position is honoured among the items that share it rather than published verbatim.

**`sidebar_position`, `sidebar_after` and `sidebar_before` are checked at upload**, before a page is
published: a position has to be a finite number, a reference a single, non-blank name, and - for a system's, a
component's or a library's documentation - a reference has to name a page of the same chapter of this set, a
generated sibling of it, or a currently stored microsite of it; a set of references must not introduce a cycle.
The set the upload replaces does not count, because none of its pages survives the upload. See
[the validation rules](upload-validation.md#a-pages-own-placement).

**An item whose reference names nothing, or that sits in a cycle, is an orphan.** It sorts by title at the end
of the chapter, after every free item, instead of where it meant to be. The upload check cannot rule this out
completely: a microsite a page names may be removed later, a generated sibling may not be written in every
environment, a microsite's own `sidebar-after` and `sidebar-before` are not checked against the chapter, and a
set taken over before these checks existed was never checked.

## A chapter's landing page lists its own contents

**Every chapter landing page lists the chapter's contents, in exactly the order above.** The list is rendered
from the sidebar by the site template rather than written into the page: it is always the same items, order
and labels as the navigation, and it can never link to a page the build left out - a link written into the
page instead could, and would fail the build of the whole part.

A page gets the list below it when its front matter carries `doc_chapter_contents: true`. An upload cannot
set that key - every `doc_*` key is outside the front-matter allowlist - so only a chapter's own generated
landing page, or the fallback the service writes for a chapter it generates nothing into, ever shows one. A
chapter holding only uploaded pages gets exactly that fallback, and lists them all the same.

## Groups

**A folder directly below a chapter that holds at least one page is a group** - one level; a page inside a
group's own folder is refused before this ever matters. A folder holding only assets is not a group, and stays
an asset folder exactly as before.

**A group's label and placement come from its own landing page** - `index.md`, `readme.md`, or a page named
like the group's folder, by the same rule a chapter's landing page is read by. That page's `title` is the
group's label, and its `sidebar_position` / `sidebar_after` / `sidebar_before` place the *group* - not the
page - among the other items of the chapter: a generated sibling, an uploaded page, a microsite, or another
group, by its stripped folder name. Without an uploaded landing page, the group is labelled by its own folder's
stripped name and placed among the items with no reference, and the site generator writes a landing page for
it instead. **Either way, the landing page carries `doc_chapter_contents: true`** - added by the site
generator, since every `doc_*` key is outside the front-matter allowlist and an upload cannot set it itself -
so the same component lists the group's pages below it, uploaded landing page or generated one alike.

**A reference inside a group names only that group's own pages.** It cannot reach a page of the chapter, a
generated sibling, a microsite, or another group - moving a page between levels means moving the file, not
rewriting a reference. The group's own pages are ordered among themselves exactly as a chapter's own are,
with no generated sibling and no microsite of their own to place themselves around.

A group starts collapsed in the sidebar. See [the upload rules](upload-validation.md#groups) for what is
refused - at most one landing page, no two pages of one group at one route, and a group's own stripped name
kept apart from every document of its chapter.

## A subject the architecture model does not know

A team can write documentation before anything is deployed. Then the architecture model holds no such system
or component - no importer has ever seen it - and there is nothing to generate from.

The service publishes it anyway, from the upload alone:

- the system, component or library gets its part, its tree and its place in the navigation;
- **one generated page, in chapter 1**, says that the architecture model does not hold it, what is therefore
  missing, and what makes it appear;
- every chapter the team wrote is there, exactly as it would be for a subject the model knows.

Nothing has to be uploaded again when the subject does appear in the model. The next build finds it and writes
the generated chapters beside what the team wrote.

**A library is always in this position.** It publishes no artifact and is deployed nowhere, so no importer can
see it and all twelve of its chapters are the team's. What the service generates for one is the frame and a
single page: the version, the repository, the branch and the commit its upload named.

## Removing documentation

Nothing is removed because it is old. A component that publishes once and stays stable for a year is the
normal case, and an age rule would delete exactly the documentation of the teams who got it right. What does
remove documentation without anyone calling for it is its subject leaving the architecture model - see
[When a subject is undeployed](#when-a-subject-is-undeployed).

Three calls remove documentation, and they are different things:

|                                    | What it removes                                                    | Who may                                         |
| ---------------------------------- | ------------------------------------------------------------------ | ----------------------------------------------- |
| `DELETE /api/docs/custom/sets`     | One set. Its pages stop being served on the next build             | the system's pipeline, or a sites administrator |
| `DELETE /api/docs/custom/subjects` | Every set of one subject                                           | the system's pipeline, or a sites administrator |
| `DELETE /api/docs/custom/systems`  | Everything of one system: its own, its components', its libraries' | a sites administrator                           |

A subject that has been documented stays in the catalogue with no set until it is removed itself, which keeps
the record of what was once published. All three ask for the part and the shell to be built - nothing takes a page off a
part that is already published, and the shell's systems index has to drop a system with nothing left. A system
the site no longer has at all is not built again: its part is removed like that of a
[system that left the landscape](generation.md#a-part-the-site-no-longer-has).

**Why an administrator at all.** The write role is granted to the pipeline of a system so that a team can only
change its own documentation, and that is the right rule while there is a pipeline. There is not always: a
repository gets archived, a component gets renamed, a team is disbanded - and the set stays current with
nobody left holding the role. Removing every set of a system is the administrator's alone, because it takes
away what several teams may own.

See [API](api.md) for the parameters.

## When a subject is undeployed

Documentation follows the deployment of what it documents, so nobody has to call the endpoints above for a
system or component that was undeployed. There is no schedule of its own: it is decided after every
successful import of an environment's [architecture model](architecture-import.md), also one that found the
model unchanged.

Every system and component documentation was uploaded for is in one of three states:

| State                 | What it means                                                       | Documentation                  |
| --------------------- | ------------------------------------------------------------------- | ------------------------------ |
| `AWAITING_DEPLOYMENT` | uploaded, and not seen in the model yet                             | kept, and every upload added   |
| `DEPLOYED`            | in the model - a system only while it has at least one component there | kept, and every upload added   |
| `UNDEPLOYED`          | was deployed, and has left the model                                | all of it removed              |

- **An upload** records a subject it does not know - and, for a component's documentation, its system - as
  awaiting deployment. One that is undeployed is awaiting deployment again; one awaiting deployment or deployed
  stays as it is.
- **An import that finds a subject in the model** marks it deployed, whether it was awaiting deployment or
  undeployed.
- **An import that no longer finds a deployed component** marks it undeployed and removes every set of it.
- **An import that finds a deployed system with no component left** marks it and all its components
  undeployed, and removes its own documentation and that of all its components - unless one of its components
  is awaiting deployment: then the system stays deployed, and only its departed components lose their
  documentation.
- **Library documentation is never removed** by a deployment, not even when its system leaves the model: a
  library is deployed nowhere and has no state of its own. Only `DELETE /api/docs/custom/subjects` or
  `DELETE /api/docs/custom/systems` remove it.

Being absent from the model says nothing about a subject awaiting deployment: documentation is often uploaded
before what it documents is deployed anywhere. The removal rechecks under the row's lock that the subject is
still deployed, so two imports deciding about the same subject at once remove nothing twice.

**The model of a site is the union of its environments.** A subject is in the model when any environment of
the site has it, and gone only when none of them has it any more. Nothing is removed unless every environment
of the site with an architecture repository has been imported successfully and their models together are not
empty - an import that failed and stored nothing must never read as every system having left at once.
"Imported successfully" means at least once, ever, not recently: an import stuck failing is covered by its own
alert (`jeap_doc_architecture_import_last_success_age_seconds`, see [the architecture import](architecture-import.md)),
not by this guard.

Documentation uploaded before this was introduced starts out awaiting deployment, so nothing is removed by
upgrading: a subject has to be seen deployed after the upgrade before its documentation can be removed.

## Related

- [Uploads](uploads.md) - what happens to a bundle, and what a retry does
- [What an upload is validated against](upload-validation.md) - the rules, per template
- [Structure templates](structure-templates.md) - the chapters, and where each kind of subject is served
- [Generating the documentation](generation.md) - when a build runs and what it publishes
- [Configuration](configuration.md), [API](api.md), [Operating the bucket](operating-the-bucket.md)
