# The governance site

**Contents**

- [Configuring it](#configuring-it)
- [Its pages](#its-pages)
- [Highlighting](#highlighting)
- [The score history chart](#the-score-history-chart)
- [Related](#related)

This page is about **the site the governance import publishes** - its configuration and its pages. For what is
read from the Governance Service and when, see [Importing the Governance Service](governance-import.md).

## Configuring it

A site with `content: governance` is generated entirely from what the governance import stored: no system, no
upload, no structure template. It is shell only - one part, one build - and has **no environments**, because the
Governance Service is read once per instance rather than once per stage:

```yaml
jeap:
  doc:
    sites:
      governance:
        title: Governance
        per-environment: false
        content: governance
        publish-on-upload: false
```

`per-environment: false` is required: a site with `content: governance` and environments configured fails the
service at startup, naming the site - the same rule a [static site](configuration.md#where-a-sites-content-comes-from)
follows, and for the same reason. `publish-on-upload` has no effect either way, since nothing is ever uploaded for
this content; it is written here only to say so.

The site refuses to start if `jeap.doc.governance.url` is not configured - a site with nothing that could ever
publish it is a configuration mistake, not a legitimate instance. See
[Configuration](configuration.md#the-governance-service) for the upstream itself.

## Its pages

Every segment below is a slug. A name or a rule ID the Governance Service delivers that cannot be made into one
gets no page and is logged, rather than failing the whole build over it. The same happens where two different
names would land on the same page, and to a system or rule whose own resource could not be read. Such a system
or rule is still listed on its overview, and a component page still shows its state of such a rule, but as text
rather than as a link: the build fails on a link to a page that does not exist.

| Page           | Path                            | Shows                                                                                                                                                                                                                                                                                                                            |
|----------------|---------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Introduction   | `/`                             | What the site shows, links to the systems and the rules, and the time the scores are as of                                                                                                                                                                                                                                       |
| System scores  | `systems/`                      | Every scored system, best score first, with score and trend, linking to its page where it has one; the time the scores are as of                                                                                                                                                                                                 |
| System page    | `systems/<system>/`             | Overall (score and trend), the component scores, best score first, linking to their pages, and the score history chart                                                                                                                                                                                                           |
| Component page | `systems/<system>/<component>/` | The component's score, trend and score history; every rule evaluated for it, with state and comment, and the further lines of a rule's result as a bullet list under the table                                                                                                                                                                                                                              |
| Rules overview | `rules/`                        | Every known rule, active ones first by weight, linking to its page where it has one, with its weight, share, conformance rate, trend and number of exemptions; a fold per rule with exemptions                                                                                                                                   |
| Rule page      | `rules/<rule-id>/`              | The rule's name and ID; for an active rule its explanation in a fold that starts closed, weight, violation delay, parameters, conformance rate (overall and per system, best first), non-compliant components and, where it has one, its violation grace period - each system and component linking to its page where it has one |

An **inactive** rule's page shows its explanation only, open, - nothing else is known about a rule the instance does not
activate. A rule shipped without an explanation says so on its page, rather than leaving the section empty.

On an active rule the explanation sits in a fold that starts closed, so the conformance facts are near the top.

A rule's explanation is the Markdown the Governance Service delivers with it, rendered with its own structure -
headings, lists, emphasis - intact. Each of its headings is moved one level down, so the rule's name stays the
only top-level heading on its page. A raw HTML tag left over in it is shown as the text it is, never as an
element: nothing on a generated page executes markup this service did not write itself. A link or an image in
it is kept only when it points to a web address (`http`, `https` or `mailto`) or to an anchor; any other shows
its text instead, for the same reason as above. That holds for a link wrapped over two lines, for one nested in
another's text and for a link reference definition too. A code block or a container the explanation leaves
open is closed at its end, so it cannot take in the rest of the page.

## Highlighting

A score or a conformance rate carries a coloured marker before the value, with the thresholds the Governance
Service's own reports have always used:

| Marker | From       |
|--------|------------|
| 🟢     | 80 percent |
| 🟡     | 50 percent |
| 🔴     | below      |

A rule's state carries a marker and a label of its own: ✅ Compliant, ❌ Non-compliant, ⏸️ Paused, ⛔ Disabled,
❔ Unknown. A trend carries an arrow before the word a reader understands without a legend: ↑ Up, ↓ Down,
→ Stable. None of it colours a table cell - a marker is plain text, so it survives a sorted or filtered table the
same way the value beside it does.

## The score history chart

A system's and a component's score history is a line chart of the daily scores over the trend period, and
nothing else - there is no table of the daily values. It is written as a fenced Mermaid definition on the page itself, in the `xychart-beta` syntax the
pinned Mermaid version renders. The chart is drawn by the browser, and `MermaidChartBrowserIT` checks in a real
browser that this syntax is drawn.

The chart sets two things of its own in the definition's front matter, the same in light and dark mode. The line
is drawn in `#3498db`: the light theme's own first line colour is a pale grey that barely shows on white. And
the days on the x-axis stand upright, because a month of dates written side by side overlaps:

```mermaid
---
config:
  xyChart:
    xAxis:
      labelRotation: -90
  themeVariables:
    xyChart:
      plotColorPalette: "#3498db"
---
xychart-beta
    title "Score history"
    x-axis ["2026-11-01", "2026-11-02", "2026-11-03"]
    y-axis "Score (%)" 0 --> 100
    line [68, 70, 72]
```

## Related

- [Importing the Governance Service](governance-import.md)
- [Configuration](configuration.md#the-governance-service)
- [Security](security.md#the-governance-webhook)
