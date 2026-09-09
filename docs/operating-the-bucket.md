# Operating the bucket

The doc service keeps four kinds of thing in one bucket, and they have different lifetimes. The uploaded
bundles are expired by a lifecycle rule; the published sites are removed by the service and by nothing else, and
there is deliberately **no age rule over them at all** - see below. So on that prefix there is no fallback for
what the service never gets to remove, and the little that escapes it has to be removed by hand.

**It is provisioned with the bucket, not by the service.** This page says what to provision.

## What is in the bucket

| Prefix     | What it is                                | Removed by                                                                                                                                                   |
|------------|-------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `uploads/` | The bundles as they arrived               | The bucket. The service removes the *record* of an upload after `jeap.doc.upload.housekeeping.retention`; the bundle it points at has to outlive that record |
| `sites/<site>/<build>/` | The generated parts of a site, one prefix per build | The service, down to `jeap.doc.build.retention` per part, after every successful build. A build that *fails* removes its own prefix; one whose instance is killed cannot, and leaves it behind |
| `sites/<site>/shared/`  | The files every part of a site emits identically - the bundles, the site's images and its branding. Written by whichever part build finds them changed or missing; one that is already stored with the same bytes is not written again | **Nothing.** See [The shared prefix grows](#the-shared-prefix-grows) below |
| `sites/<site>/search/<index>/` | One search index of a site, one prefix per index - [Search](search.md). Written by the pass that published the site, not by a build | The service. It keeps `jeap.doc.search.retention` of them and deletes the rest as soon as the replacement is being served. What an **interrupted** run left - an instance killed between writing its files and recording that it had - is removed by `SearchIndexHousekeeping` on the nightly clean-up |

`uploads` is `jeap.doc.storage.upload-prefix` and `sites` is `jeap.doc.storage.site-prefix`, and an instance
may set either to something else - which is why the rules below name a tag rather than a prefix.

**Every object the service writes carries the tag `jeap-doc-content`** - `upload` or `site` - so that a rule can
name what it is expiring rather than a prefix an instance configures for itself. A search index is tagged
`site`, and the rule below therefore covers it: an age rule would take the search off a site nobody has had to
republish, for the same reason it would take the site itself off.

## The rules to provision

| Tag                       | Expire after                                                                                    |                                         |
|---------------------------|-------------------------------------------------------------------------------------------------|-----------------------------------------|
| `jeap-doc-content=upload` | **A few days longer than `jeap.doc.upload.housekeeping.retention`** (14 days by default, so 21) | An upload must never outlive its bundle |

**No rule over `jeap-doc-content=site`** - see below. The service's own retention removes what it superseded,
and nothing else may.

Two more that are always safe and easy to forget:

- **Abort incomplete multipart uploads after one day.** Parts left by a killed process are billed and are
  invisible to a listing.
- **Expire noncurrent versions after one day**, on a bucket with versioning. Without it every delete becomes a
  delete marker and nothing ever actually leaves.


## Why there is no age rule over the sites

**A published part is written once and then left alone.** A build whose content hashes to what is already
published does not run the site generator and uploads nothing, so the objects a site is serving keep the date
of the build that last *changed* that part - which for a part nobody edits is as old as the part. An age rule
over `jeap-doc-content=site` therefore expires exactly the documentation nobody has had to touch: the objects
go while the database still says the part is published, every page of it answers `503` or `404`, and the next
build finds its digest unchanged and publishes nothing - so it never heals. Only a forced build does.

That is what makes an age rule unsafe **at any value**, and not merely too short a one. It used to be two days,
justified by a site being regenerated several times a day; publishing in parts abolished that, deliberately -
see [Generating the documentation](generation.md).

**A search index is the same story with one difference.** It is written under a prefix named after the run
that produced it and made current by one row, so an age rule over it would expire the index of a site that has
not changed - and every filename in it is content-hashed, so a browser holding the manifest would ask for
chunks that are no longer there. What is different is that a run can die before its row says anything, leaving
a prefix nothing names: **that** is what the nightly `SearchIndexHousekeeping` clears, and it is safe to do by
age because it only ever touches runs that were never published.

**What removes a superseded build is the service.** Its retention deletes the objects of a publication it has
replaced, once the replacement is being served, and it is the only thing that knows which those are. What a
lifecycle rule can still do safely on this prefix is the two housekeeping rules above - incomplete multipart
uploads and noncurrent versions - because neither is addressed by anything.

**Nothing under `sites/` is a source of truth**, so nothing is *lost* either way: a generated site is derived
from the site template and the architecture model. The cost of getting this wrong is a site that is offline
until somebody notices and forces a publication, not data.

## The shared prefix grows

`sites/<site>/shared/` is written by every part build and removed by nothing. The fixed names - the site's images
and its branding - are overwritten in place and cost nothing over time. What accumulates are the **content-hashed
bundles** of the site template: a new version of the template emits new names, and the names of the version before
it stay behind for ever.

So the prefix grows by roughly one template's worth of assets - a few megabytes - **per release of the site
template that changes them**, not per build and not per part. On a site released a handful of times a year that
is small enough to leave alone, which is why nothing removes it.

**It is cleaned up by hand**, and the safe moment is well after a release, when every part of the site has been
rebuilt at least once on the new template:

1. list `sites/<site>/shared/`, and
2. delete the hashed names no page of the current publication references.

An age rule cannot do this - the newest publication of a part that never changes is as old as that part, and
references the bundles of the template it was built with.

## The rule that must not be written

> **No age-based rule over the uploaded documentation once it is more than a staging area.**

Today `uploads/` holds bundles that are read once and can expire. When the doc service starts taking uploaded
documentation over into a *current* set of documentation sources, those sources become the only copy - the bundle
they came from has its own, shorter expiry - and the current version of a set can be arbitrarily old, because a
component that publishes once and stays stable for a year is the normal case. An age rule over that prefix would
delete exactly the documentation of the teams who got it right and left it alone.

Age cannot tell an orphan document from a well-kept one - and, since a part that has not changed is no longer
rebuilt, it can no longer tell a superseded build from a current one either.

## Related

- [Generating the documentation](generation.md) - what writes under `sites/`
- [Uploads](uploads.md) - what writes under `uploads/`
- [Observability](observability.md) - the alarm the site rule relies on
- [Configuration](configuration.md)
