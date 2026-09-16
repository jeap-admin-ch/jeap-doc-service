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

| Key                                                                                                              | What it is                                                    |
| ---------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| `title`, `description`, `sidebar_label`, `tags`, `keywords`                                                      | Kept from the upload, exactly as written                      |
| `sidebar_position`                                                                                               | Assigned by the service, from the sorted titles - see below   |
| `doc_status`                                                                                                     | `custom`, against the `generated` of a page the service wrote |
| `doc_source`, `doc_source_repository`, `doc_source_ref`, `doc_source_revision`, `doc_version`, `doc_uploaded_at` | Where the page came from                                      |

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
| Where it is ordered | Among the uploaded pages of its chapter, by its label - the two are one list |
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

**The pages of a chapter are sorted by their titles**, and the service assigns each page's
`sidebar_position` from that order.

It has to, because Docusaurus does not. An autogenerated sidebar is sorted by `sidebar_position` and then by
**file name**, so `zebra.md` titled *Alpha* would come last. And `sidebar_position` is not on the front-matter
allowlist, so an upload cannot set it: the order of a chapter lives in one place, and that place is the
titles a reader sees.

A page with no title is sorted by its file name, which is what Docusaurus falls back to for the heading
anyway, and the file name breaks a tie between two pages with the same title - so the order never depends on
how the archive happened to list them.

**And the uploaded pages of a chapter stand after the ones the service generates into it.** Their positions
are offset past whatever the template writes there, because Docusaurus breaks a tie between two equal
positions by file name - the one thing assigning a position is meant to take out of it.

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
normal case, and an age rule would delete exactly the documentation of the teams who got it right.

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

## Related

- [Uploads](uploads.md) - what happens to a bundle, and what a retry does
- [What an upload is validated against](upload-validation.md) - the rules, per template
- [Structure templates](structure-templates.md) - the chapters, and where each kind of subject is served
- [Generating the documentation](generation.md) - when a build runs and what it publishes
- [Configuration](configuration.md), [API](api.md), [Operating the bucket](operating-the-bucket.md)
