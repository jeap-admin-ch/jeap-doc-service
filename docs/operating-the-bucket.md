# Operating the bucket

The doc service keeps three kinds of thing in one bucket, and they have three different lifetimes. Two of them
are removed by the service itself; a lifecycle configuration is the fallback for what it never gets to remove -
an instance killed at the wrong moment, a run that never reached its clean-up - and it is the only clean-up that
keeps working when the service does not.

**It is provisioned with the bucket, not by the service.** This page says what to provision.

## What is in the bucket

| Prefix     | What it is                                | Removed by                                                                                                                                                   |
|------------|-------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `uploads/` | The bundles as they arrived               | The bucket. The service removes the *record* of an upload after `jeap.doc.upload.housekeeping.retention`; the bundle it points at has to outlive that record |
| `<site>/<build>/` | The generated parts of a site, one prefix per build | The service, down to `jeap.doc.build.retention` per part, after every successful build |
| `<site>/shared/`  | The files every part of a site emits identically - the bundles and the site's images. Written by every part build under the same names | Nothing yet: they are overwritten by every build that emits them, and a name nothing references any more is a small leak. See [Generating the documentation](generation.md) |

**Every object the service writes carries the tag `jeap-doc-content`** - `upload` or `site` - so that a rule can
name what it is expiring rather than a prefix an instance configures for itself.

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

**What removes a superseded build is the service.** Its retention deletes the objects of a publication it has
replaced, once the replacement is being served, and it is the only thing that knows which those are. What a
lifecycle rule can still do safely on this prefix is the two housekeeping rules above - incomplete multipart
uploads and noncurrent versions - because neither is addressed by anything.

**Nothing under `sites/` is a source of truth**, so nothing is *lost* either way: a generated site is derived
from the site template and the architecture model. The cost of getting this wrong is a site that is offline
until somebody notices and forces a publication, not data.

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
