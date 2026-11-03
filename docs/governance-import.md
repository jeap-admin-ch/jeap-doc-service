# Importing the Governance Service

**Contents**

- [What is read, and stored](#what-is-read-and-stored)
- [Unchanged resources are not read again](#unchanged-resources-are-not-read-again)
- [The webhook, and the schedule behind it](#the-webhook-and-the-schedule-behind-it)
- [An import already running absorbs a further ask](#an-import-already-running-absorbs-a-further-ask)
- [When something goes wrong](#when-something-goes-wrong)
- [What it reports about itself](#what-it-reports-about-itself)
- [Related](#related)

## What is read, and stored

The Governance Service's `/docs-api` has two indexes - the systems and the rules it scores - and a resource
behind every entry of either: one system, or one rule. All four are stored as they arrive, byte for byte: the
import parses nothing but the two indexes, just enough to read the entries and their entity tags, and the
generator reads the stored bytes back when it writes a page.

Unlike the architecture model there is no environment: the Governance Service is read once per instance, the
way the governance site has no environments either (`per-environment: false`).

A system or rule the index no longer lists is deleted with its stored resource.

## Unchanged resources are not read again

Both indexes carry the time of the last completed scoring run, so they change at every run - but a system or
rule resource moves far less often, and the import reads only what did:

1. Both indexes, with `If-None-Match` against the tag held for each. A `304` is "unchanged" and nothing else
   is read; a `200` is parsed for its list of entries, and the raw body is stored so a page can read the
   scoring time off it later.
2. Of the entries an index lists, only those whose own tag differs from the one held for it are fetched - with
   `If-None-Match` again, in case it changed once more in between.

An index that lists nothing while something is stored for it is not believed: the stored copies are kept and
the index is asked again, unconditionally, on the next run. The alternative - deleting everything a run reads
as "nothing" - turns an upstream that answered wrongly, even once, into every page of the site going missing.

## The webhook, and the schedule behind it

`POST /api/governance/webhook` is called by the Governance Service after every completed scoring run and
imports the governance data **at once** - see [the API](api.md#reporting-a-completed-governance-scoring-run)
and [Security](security.md#the-governance-webhook) for the call itself. `jeap.doc.governance.import.cron`
(default `0 55 6-20 * * MON-FRI`, the Governance Service's own reporting job's expression) is the **safety
net**: a lost webhook call is caught up with by the next scheduled import. With the default that is within the
hour on a weekday, and the next weekday morning for a scoring run after the last import of a day. **Empty
means never.**

Either one asks for every part of the `governance` site to be published after every import that left the
systems or the rules in a complete state - whether or not anything changed. The build is digest-guarded, so
one over unchanged content is skipped. Asking only after a change would lose the request for good if it failed
once, because the next import would find both indexes unchanged. And both indexes carry the scoring time,
which rebuilding keeps right on the overviews.

## An import already running absorbs a further ask

The schedule and the webhook both hand their ask to the same single-threaded pickup, the way a build trigger
and the build poll both hand theirs to the build pickup. An ask that arrives while an import is already running
**merges with it** rather than starting a second one beside it: the flag that says "start one more" is cleared
before a run starts, so a webhook call reporting a scoring run the import in flight had not yet read is not
lost - it leads to exactly one further import once that run ends.

**Only one instance imports at a time.** The run itself takes the lock `governanceImport`, leased for five
minutes. A scheduled import that does not get the lock does nothing: every instance's schedule fires at once,
and one of them importing is enough. An import the webhook asked for is different. The run holding the lock
may have read the indexes before the scoring run the webhook reports, so the import is tried again 30 seconds
later, and again after that, until this instance gets the lock and imports. A retry still waiting when the
instance stops is dropped, and the next schedule catches up.

A webhook call that arrives before the pickup has started is kept and imported once it starts. One that arrives
while the instance is stopping is not taken, and its event ID is not recorded either - so the Governance
Service's redelivery of it, to this instance or another, is imported rather than ignored as a repeat.

## When something goes wrong

Nothing an import does can fail a build: the previously published governance site stays served.

| Situation                                                  | What happens                                                                                                                                                                              |
|------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| The Governance Service is unreachable, or answers an error | The run is abandoned for that kind (systems or rules); what is stored is kept, logged as an `ERROR` naming the resource and the answer                                                    |
| An index lists nothing while something is stored           | The stored copies are kept; the index is asked again, unconditionally, next time                                                                                                          |
| An index has an entry without an identity or an entity tag | The whole index is refused like an unreachable service; what is stored is kept. An entry left out would be taken as withdrawn and deleted                                                 |
| One resource cannot be fetched or replicated               | Nothing of that run is stored: the index, the other resources and the removals wait. The run is `PARTIAL` and no success, and the index is asked unconditionally until every entry works |

**An index and what it lists are stored together or not at all.** A run first reads everything that moved, and
only then writes the index, the resources and the removals in one transaction. So a failing fetch never leaves
new scores beside old detail pages, or a system deleted whose index entry changed. The price is that a single
resource that cannot be replicated - one over the size limit, say - holds its whole kind at the last complete
state until it can, and the age of the last success is what says so.

**A build reads one snapshot.** The generator reads both indexes and every resource they list in one
repeatable-read transaction. An import that commits while a build is reading is seen by all of that build's
reads or by none, so the overview never shows a score beside a detail page from a different import.

`ERROR` and not the architecture import's `WARN` for the same shape of failure: the Governance Service is a
dependency this service alarms on, since a scoring run readers are waiting for would otherwise stay invisible
until someone asks why the site has not changed.

## What it reports about itself

`jeap_doc_governance_import_last_success_age_seconds` is the one to alarm on, tagged `kind` (`system` or
`rule`). Bound only where a Governance Service is configured at all.

```promql
# The systems or the rules have not been imported for four hours - or never have been, which reads as NaN.
jeap_doc_governance_import_last_success_age_seconds > 4 * 3600
  or absent(jeap_doc_governance_import_last_success_age_seconds)
  or jeap_doc_governance_import_last_success_age_seconds != jeap_doc_governance_import_last_success_age_seconds
```

Beside it: `jeap.doc.governance.import` times one run and tags it with `kind` and `result`, `.items` counts
what was stored, confirmed unchanged, removed or skipped (`skipped` only in a `PARTIAL` run), and `jeap.doc.governance.resources` is how many are
stored. A sudden drop in the last of those is a Governance Service that lost its data, which no failure
counter catches because the run succeeded.

**What each run did is on its row**, as `last_outcome` of `governance_import` - `CHANGED`, `UNCHANGED`,
`PARTIAL` or `FAILED`. It is not derivable from the timestamps alone, the same reason the architecture import's row keeps
its own outcome column. An outcome written by a newer version of the service reads as none rather than making
the row unreadable.

## Related

- [API](api.md#reporting-a-completed-governance-scoring-run)
- [Security](security.md#the-governance-webhook)
- [Configuration](configuration.md#the-governance-service)
- [The governance site](governance-site.md) - what is published from what this page describes
- [The scheduled jobs](scheduled-jobs.md)
- [Observability](observability.md#the-governance-import)
- [Importing the architecture repository](architecture-import.md) - the same shape of import, over a landscape instead of a scoring run
