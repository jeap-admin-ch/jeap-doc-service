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
| `sites/`   | The generated sites, one prefix per build | The service, down to `jeap.doc.build.retention` per site, after every successful build                                                                       |

**Every object the service writes carries the tag `jeap-doc-content`** - `upload` or `site` - so that a rule can
name what it is expiring rather than a prefix an instance configures for itself.

## The rules to provision

| Tag                       | Expire after                                                                                    |                                                                                                                                                                        |
|---------------------------|-------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `jeap-doc-content=upload` | **A few days longer than `jeap.doc.upload.housekeeping.retention`** (14 days by default, so 21) | An upload must never outlive its bundle                                                                                                                                |
| `jeap-doc-content=site`   | **2 days**                                                                                      | A site is regenerated several times a day, so anything two days old is a build nobody will serve again - and the retention will normally have removed it hours earlier |

Two more that are always safe and easy to forget:

- **Abort incomplete multipart uploads after one day.** Parts left by a killed process are billed and are
  invisible to a listing.
- **Expire noncurrent versions after one day**, on a bucket with versioning. Without it every delete becomes a
  delete marker and nothing ever actually leaves.


**The two days assume the site is on a schedule.** A site that configures an empty `publication-schedule` is
published only when something is uploaded to it, which is a supported thing to want - and for such a site the
rule is a timer on its documentation. Two days after the last upload the objects are gone while the database
still says the site is published, so every page answers `503` until someone uploads again, which for a stable
component can be months. Either keep a `publication-schedule` on every site, or size the `jeap-doc-content=site`
expiry for the longest plausible gap between uploads to the sites that have none.

## Why two days is safe for the sites, and what that implies

**Nothing under `sites/` is a source of truth.** A generated site is derived from the site template and from the
architecture model, so the worst a rule that fires too early can do is take the site offline until the next
build - it loses nothing.

That said, the number means what it says: **if no build of a site succeeds for two days, that site goes
offline**, and `GET /` answers `503` until one does. That is intended rather than an accident. A site that has
not regenerated in two days on a schedule of several a day is broken and should already have been alarmed on -
see [Observability](observability.md), where the first alarm fires after hours, not days.

## The rule that must not be written

> **No age-based rule over the uploaded documentation once it is more than a staging area.**

Today `uploads/` holds bundles that are read once and can expire. When the doc service starts taking uploaded
documentation over into a *current* set of documentation sources, those sources become the only copy - the bundle
they came from has its own, shorter expiry - and the current version of a set can be arbitrarily old, because a
component that publishes once and stays stable for a year is the normal case. An age rule over that prefix would
delete exactly the documentation of the teams who got it right and left it alone.

Age can tell a superseded build from a current one, because a site is rebuilt on a schedule. It cannot tell an
orphan document from a well-kept one.

## Related

- [Generating the documentation](generation.md) - what writes under `sites/`
- [Uploads](uploads.md) - what writes under `uploads/`
- [Observability](observability.md) - the alarm the site rule relies on
- [Configuration](configuration.md)
