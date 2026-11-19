# Reaction graphs

How reaction snapshots are published, displayed and explored in the documentation site.
For observer configuration and replication, see [Configuration](configuration.md#the-reactions)
and [Architecture import](architecture-import.md).

## Complete reaction graph data

The shell publishes `reaction-graph.json` at the root of each environment: at the site root for its main
environment, and under `/<environment>/` for the others. A non-default site retains its `/site/<id>/` prefix,
and deployment context paths apply as for every site file. Clients resolve the file against the site's base
URL and the environment's route prefix, never against the host root.

The file is a single JSON response, not a manifest pointing at separately changing chunks. It contains:

- `formatVersion`: currently `1`. When available, `identityScheme`: `observer-v1` or `observer-v2`.
- `site` and `environment`: the publication context.
- `available`: whether a stored graph exists for a configured observer.
- `snapshot`: SHA-256 of the serialized payload before this field is added.
- When available, `importedAt`, `graph`, `ownership` and `ownershipComplete`.

The same domain and JSON shape represents both APIs. `graph` carries its `identityScheme`, `nodes` and `edges`.
Each node has `identity` (`{nodeType, key}`), its representation `nodeType`, and either `interfaceDetails` or
`reaction`. The identity's `nodeType` names the identity namespace: V1 uses `MESSAGE` and `REACTION`, V2 uses
`INTERFACE` and `REACTION`. Thus a V2 generic interface becoming a typed message or HTTP operation retains
its identity. Keys are opaque strings even when V1 supplied integers; V2 IDs are not durable across database
resets or environments.

- `interfaceDetails`: `interfaceType`, `fqn`, optional `message` (`messageType`, `variant`) and optional `http`
  (`provider`, `method`, `path`). Unknown V2 interface kinds retain their type, FQN and connections.
- `reaction`: `system`, `component`, numeric `median`, `status` (`ACTIVE`, `SUPERSEDED`, `UNKNOWN`),
  `reactionModelVersion`, `unidentifiedActionsRatio` and optional `groupKey`. V1 supplies only component and
  median; its status is `UNKNOWN`, and missing metadata remains null. No event/command type is guessed.
- `edges`: `edgeType` (`TRIGGER`, `ACTION`, `PREDECESSOR`) and `source`/`target` identities. A predecessor edge
  goes from successor to predecessor; it is history, not execution flow. Unknown V2 edge kinds are ignored.

`ReactionGraphJson` translates the typed `ObservedReactions` domain model into this browser format.
The domain keeps interfaces, reactions, triggers, actions and history in separate collections; it carries
no JSON mapper or browser-node representation.

The full graph remains intact, including superseded reactions and nodes the architecture model cannot place.
Visibility filtering belongs to the viewer. The current V2 provider does not promise a group key; if a later
provider supplies one it is retained. Otherwise the viewer can group by component and trigger. This publication
does not invent an anchor or an observation window.

V1 does not supply a system per reaction. `ownership` associates reaction identities with system and component
names only where the architecture model has exactly one matching component, ignoring case. Ambiguous or
unknown components have no ownership entry and make `ownershipComplete` false. Consumers must disclose this
limitation instead of treating an unresolved owner as an absent reaction. V2 ownership comes directly from
the observer; it is never overwritten or guessed from the architecture model. Labels are JSON data; the viewer renders them as text.

The file lives in the shell's ordinary publication, not in shared assets. The content digest includes it, so
changed graph data rebuilds the shell. System parts do not publish copies. The existing successful-build
switch publishes the whole file atomically; readers get one snapshot, never a mixture of revisions. Failed
imports keep the stored graph, and failed builds keep the last successful publication. If no graph is stored,
`available` is false and no `graph` is included. Existing reaction chapters and absence behaviour remain unchanged.
Disabling an observer makes subsequent publications unavailable rather than republishing its old data.

The file follows the site's existing access policy, cache revalidation and retention. The browser needs no
observer access. There is no historical graph lookup or browser snapshot cache. A future V2 migration may
invalidate links referencing V1 node identities. Further exploration actions are separate work.


## Browser reaction viewer

REST provider destinations are indexed once per environment and included only for HTTP interfaces in the
entry view. Views without HTTP observations carry no REST destination metadata. Provider names shared by
multiple components remain unlinked, even if only one of them exposes REST. Adding an unrelated provider
does not change the generated reaction-viewer metadata. Component, message and REST links use a stable order,
so reordering a snapshot does not change the generated pages or rebuild unchanged system parts.

The existing system and component reaction chapters and each message variant section include a React viewer.
The viewer title shows the system name followed by `(System)`, the component name, or the message and its variant.
Its subtitle counts reactions belonging to that system or component. For messages, it counts only reactions
directly triggering on or publishing the selected message variant, once each. Superseded reactions are excluded,
and collapsing a group does not change the count.
It loads the complete `reaction-graph.json` of the current site and environment once per browser application,
then derives independent entry states for each embedded viewer. System and component entries start with
collapsed reaction groups and show their adjacent interfaces. Message entries show only the selected
variant, its incoming and outgoing edges, and the collapsed reaction groups at their other ends. Other
members of the same group and other interfaces of those reactions stay outside the selection, including after expanding a group.
Their tables follow the same restriction.
Layout and canvas creation wait until the viewer approaches the viewport; scrolling away retains its state.
Sites without environments show an unavailable message and keep the generated table without fetching a graph.
Groups can be collapsed independently through their context menu. System entries exclude foreign reactions.
Reaction tables remain below the viewer. Once the graph renders, each table uses the same JSON and shows the
individual reactions currently visible in its viewer. Trigger and action cells include only connections
whose interface endpoint is also visible. Collapsing a group removes its rows; expanding it
restores them. Search and sorting remain available and keep their settings as the graph changes. Other
variants' tables are independent. When all groups are collapsed, the table explains why it has no rows.
The generated table of the initial selection remains the fallback when JavaScript is disabled or the viewer
cannot load. The live table replaces it only after the graph has rendered successfully.

Grouping uses system, component and the V2 group key, falling back to the known trigger. Triggerless or
unresolved-owner reactions stay separate. Superseded reactions remain in the model but start hidden with a
count. Predecessor relationships never become flow arrows. Circular markers count collapsed reactions or
hidden reactions adjacent to a visible interface. Unknown ownership is disclosed.

Messages are compact hexagons with short tips, HTTP operations compact rounded rectangles with height adapted to their labels, generic interfaces ellipses, reactions rectangles and groups
rounded containers. The graph uses Cytoscape with a Dagre layout. Within each group, reactions are reordered when a swap
reduces crossings between their incoming and outgoing connections. Ties keep the existing order. All labels are text. Individual and bundled
arrows use `min(6, 1 + log10(1 + median))` pixels; bundles sum distinct contributing reactions and identify the
sum as an approximation. Both trigger and action arrows connect to individual reactions when expanded;
only collapsed reactions contribute to arrows at the group frame. Explicitly hidden reactions contribute no arrows. Hover tooltips show frequency details; the existing reaction table provides
the textual representation. The initial view and Reset view fit the entry selection with a margin and cap
the zoom at 100%, so small graphs are not enlarged.
Fit all shows the complete visible selection; manual pan and zoom survive presentation redraws.
After a graph action, the viewport follows its target through relayout, keeping its screen position where
space permits. Expansions pan and zoom out as needed to show the complete addition. Focus and Reset fit
their resulting selection. Back restores the viewport and node positions from before the action;
visibility and pin changes preserve zoom. A removed target falls back to its group or a surviving neighbour.
After hiding, the nearest surviving predecessor is preferred, then a successor, then the group frame.
It is briefly highlighted in the same yellow style as newly shown content, without adding a pin.
If nothing remains in that connected area, no unrelated node is highlighted.
Keyboard actions continue at that element; this does not change the semantic focus or selection.
The viewport offers zoom, fit, reset and full screen, with keyboard equivalents for zoom and pan. Groups can be collapsed and expanded
without changing the selection. The legend stays visible, including in full screen. Toolbar buttons use
icons, with tooltips and accessible names. Frequency details appear
when hovering over an arrow or a reaction. Focus and directed expansion can explore the full snapshot across system boundaries.

Toolbar controls form three separated groups from left to right: Back and Reset view; Share link and source;
zoom, Fit all, minimap and full screen. Separators have 18 pixels of space on either side. The source panel shows formatted JSON for the currently displayed projection. Export
format version 2 uses simple local IDs such as `node-1` and `group-1` for elements and their connections.
Original node identities and group metadata remain structured fields. Restore targets outside the displayed
selection use structured identities rather than strings containing further encoded JSON. This removes the
nested escaping of internal IDs while retaining normal JSON escaping for quotes, backslashes and line breaks
in actual text values. It changes the readable source export, not the published graph or shared-link format.
Copy source uses the same three-second toolbar feedback as Share link, with **Code copied** after a
successful copy. Clipboard failure selects the source text for manual copying and shows no success message.

Right-click any node to pin or unpin it, hide it, or hide the downstream subgraph. A red pin badge marks pinned elements without changing their ownership border; the legend explains the badge.
Message entries pin their selected message variant initially. Other entries and Focus start unpinned.
The **Pin** action adds or removes pins independently of the entry selection.
Several nodes and groups can be pinned at once. Hiding a pinned node uses **Unpin and hide**. A group with
pins uses **Unpin members and hide group**, which removes the group's pin and all member pins together.
Each action produces one complete semantic state for later history support; this release has no undo control.

Ordinary **Hide** removes only the chosen node (or the members of the chosen group) and its incident
edges. Direct neighbours that lose their last visible flow connection are also hidden unless pinned.
Connected siblings and other branches remain visible, even when nothing is pinned. Cleanup is not recursive;
empty group frames are omitted automatically, while frames containing visible reactions remain.

**Hide outgoing paths** follows visible outgoing arrows with cycle detection. It keeps the starting
element and stops at pinned nodes or groups; members of a pinned group are protected too. A node beyond a
pin can still be reached and hidden through another unpinned path. Upstream-only and separate regions remain.
**Hide connected subgraph** follows visible connections in both directions and includes the selected node
or group itself unless pinned. Pins stop traversal and stay visible, including members of pinned groups.
When starting on a pinned node or group, its neighbours can still be hidden while the start stays visible.
Disconnected regions remain unchanged. Group containment is not a connection between its reaction paths.
PREDECESSOR history participates in none of these operations.

Circular restore markers sit beside the visible neighbours of hidden content: incoming on the left,
outgoing on the right. Clicking one restores the indicated neighbours. Counts and arrow widths are recalculated from the current state. Boundary circles count direct neighbours outside the current selection. Clicking one adds those neighbours
and their connecting edges; successive clicks follow the chain one edge at a time.

Superseded reactions start hidden. Open their count hint to reveal a chosen reaction and its flow connections.
A revealed superseded reaction can be pinned and collapsed like an active reaction. Shared interfaces remain
visible when one contributing reaction is hidden. The status means presumed superseded; it is never inferred
from a zero median. The Expand group menu action only unfolds eligible members; it preserves explicit hiding and never reveals
superseded reactions. The numbered group marker is a separate restore action: it also restores explicitly
hidden members counted in that marker, but does not reveal previously unrevealed superseded reactions.

Right-click a group frame or one of its members for “Collapse group” or “Expand group”, according to its state.
The menu groups documentation, visibility and exploration actions with separators and leading icons.
Right-clicking the diagram or its menu suppresses the native browser menu, including outside full screen. The source view retains its native browser menu.
The menu disables actions that would not change the group. Collapsing preserves pinned members and changes
only that group; the other groups and other viewers retain their state. Collapsing preserves zoom; expanding may adjust the viewport to include the complete addition.
After selecting a group, Shift+F10 opens its menu (the first group is the initial keyboard target);
arrow keys navigate the enabled menu entries and Escape closes it. The toolbar controls adjust the presentation.
Prepared views do not offer path selection.
The minimap toggle opens an overview of the current diagram with its visible viewport marked. Clicking or
dragging moves the main viewport without changing zoom; arrow keys pan and Home fits the diagram. The map
updates after group changes, colour-mode changes and resizing, including full screen, and is independent
for each viewer. Its image is rendered from the same positioned graph at a bounded thumbnail resolution.

The source toggle shows a read-only JSON representation of the currently drawn groups, nodes, markers and
edges, together with site, environment and entry information. Node identities are also shown as structured
`nodeType` and `key` fields. Groups include `system`, `component` and `grouping`: the explicit group key,
the trigger identity and full label, or the individual reaction identity for a standalone group.
Local element IDs connect the exported elements, while structured identities describe the model references. The JSON can be copied or selected manually when
clipboard access is unavailable. This is the viewer's equivalent of the old DOT source, not an import or
share-link format. Opening and closing it preserves group state, zoom and pan; the legend remains visible.

A group's menu also offers “Go to component documentation” when its documented component has a
runtime page. The destination is supplied by the structure template using the available documentation pages,
stays within the current site and environment, and is omitted on that component's own page.
Message context menus link to the event or command documentation. HTTP context menus link to the provider's
REST API page when the model identifies one documented provider with a REST API. Unknown or ambiguous targets
show a disabled “Documentation unavailable” entry. These links also keep the current site and environment.
Arrows and reaction nodes show hover tooltips with their frequencies. The tooltip disappears when the pointer
leaves, when a menu opens, or when the viewport changes. Other nodes and groups have no hover tooltips.
Clicking adds no detail text below the diagram.

Diagram labels follow the original view: message names omit `Event` or `Command`, component names omit
`-service` or `-scs`, variants appear in brackets and reactions show their daily median as `12`.
Reaction IDs remain in the JSON source; the tooltip identifies the value as `Median per day`. Group titles sit inside
the frame above the reactions. The legend uses SVG symbols
with the same shapes and colour definitions as the canvas, including in dark mode.
It lists node types visible in the current diagram and updates when groups are collapsed or expanded;
REST is always included. Reaction arrows arrive at the left box border and leave at the right border.
Trigger arrows connect to the individual reactions while a group is expanded, and to the group frame
while it is collapsed.

Group titles contain only the component name. Interfaces and component groups of a known foreign system have a blue border; individual reactions keep their normal border,
explained in the legend. Message ownership comes from unambiguous
architecture-model assignments published with the snapshot, while reactions keep their resolved ownership.
The reference is the system/component page's system or the selected message's documented system.
Missing or ambiguous ownership is never inferred from neighbouring reactions or message names.

A malformed, unavailable or wrong-context snapshot shows `Reaction graph currently unavailable`; other page
content remains usable. The viewer does not ask the observer directly. Existing omission of reaction pages
without observations is unchanged. Entry metadata travels in an inert `reaction-view` code fence, written by
`MarkdownWriter.reactionViewer`, not as raw HTML or executable MDX.

Reaction viewers share one snapshot request per URL within a page visit. Navigating to another page
and returning creates a fresh request with HTTP cache revalidation; the server supplies an ETag and
`Cache-Control: no-cache`. Completed requests are not retained across page visits.

## Focusing and exploring reaction chains

**Focus** in a node menu starts a new selection without pinning the node. An interface shows its directly connected
reaction groups; a reaction shows its trigger and action interfaces; a group starts collapsed with its adjacent
interfaces. Focus preserves the entry used by Reset view.

Reaction groups start collapsed in new entry and path views. Newly reached groups also stay collapsed
during exploration. Use the numbered marker inside a group or **Expand group** to show its individual
reactions. A separate marker on the outgoing side, also available as **Show outgoing actions** in the group
menu, reveals the direct action interfaces of its selected, available reactions without unfolding the group.
It does not add sibling reactions or follow downstream reactions. Hidden action interfaces use the same
opener, without an additional restore marker beside the group. Back and Reset view undo this action.

**Expand incoming paths** and **Expand outgoing paths** add directed connections at a chosen edge depth,
from one to the reachable depth, capped at 50. Existing contents and pins remain. Group expansion starts
from all eligible members without unfolding the group. Already visible selections disable the action.
Superseded reactions participate only after explicit reveal; PREDECESSOR history never participates.

Path selection is reserved for the standalone Graph Explorer, including a searchable endpoint picker.
Its existing graph algorithm selects at least two distinct interfaces in a new, unpinned view. After the first selection,
candidates are interfaces reachable in either direction from an endpoint. The result includes all directed
connections between every ordered pair, including cyclic detours, without a depth cutoff. Dead-end branches
are excluded. This shows graph relationships, not an individual execution trace. Message, HTTP and generic
interfaces support the same operations.


Reaction-view expansions pan minimally and zoom out as far as necessary to show the starting point and all
new nodes and connections together. They do not zoom in. New elements are highlighted briefly. Viewport
movement respects reduced-motion preferences and stops on a pointer gesture.


## Undo, reset and share a reaction view

**Back** restores the complete semantic state before the last viewer action: the selection, visible nodes
and connections, pins, group expansion and explicitly revealed superseded reactions. Focus, Reset view and
combined unpin-and-hide actions each take one step. Each viewer keeps its newest 100 undoable steps in memory.
Each checkpoint includes the zoom, pan and node positions from immediately before that action. Back restores
them together with the semantic state, even if the reader changed the viewport after the action.
Zooming, panning, moving nodes, opening controls and typing search text add no steps. Viewer Back does not
navigate browser history. Reloading a page or opening a shared link starts with an empty undo history.

**Reset view** restores the current entry's initial selection, visibility, pins and group state. Focus keeps
that baseline. The state transition for selecting a search result establishes a new entry baseline; Back
restores the preceding view and baseline together. This applies to the Explorer's global entry search;
the local toolbar search only locates a node in the current graph and creates no history step.
Reset is itself undoable, so Back returns to the edited view.

The browser address follows semantic viewer actions, zoom and pan automatically. Updates wait until 250 ms
after the latest change, so dragging or quick successive actions produce one encoding of the latest state.
The most recently used viewer owns the link. Initial rendering and activating another offscreen viewer do
not overwrite it; an older asynchronous encoder cannot replace a newer viewer's address. Each unique target
is validated once per encoding, even when many steps refer to it. Automatic updates never write the clipboard
or add undo steps. If encoding fails or reaches a limit, the previous reaction-view fragment is removed
and the viewer shows the reason; the current graph remains usable.

**Share link** generates the current link immediately, offers it in a selectable field and copies it to the
clipboard where the browser permits. Both automatic and manual updates replace the browser address without
navigating, adding a browser-history entry or resetting the live viewers, their viewport or undo history.
Successful copying shows **Link copied**
beside the toolbar for three seconds. If copying is denied, the address and selectable link remain available
without claiming clipboard success. Later exploration updates the address automatically.
It shares one viewer, including the selected message variant on a page with several viewers. Other viewers
start in their ordinary entry views. The recipient replays the entry and every semantic step on the graph
currently published for the same site, environment and documentation page. The result is newly laid out.
Links restore the sender's zoom and the model point at the centre of the canvas. A different
canvas size changes the pixel pan to keep that point centred. Individual node positions and the sender's
undo buffer are not included. The replay script retains
steps beyond the 100-entry undo buffer; Back branches that script along with the view.

New shared views use compact JSON version 2. A shared reference table stores each entry or target once; ordered
steps contain numeric opcodes and indices into that table. These indices address the link's own dictionary,
not upstream graph IDs. The context is a tuple of site, environment, base URL and page path. The viewer and
initial entry use dictionary indices; the optional viewport is a tuple of zoom and centre coordinates.
The decoded structure expands to the same semantic references and steps before validation and replay.

The transport is `#reaction-view=d1.<payload>`: compact JSON, encoded as UTF-8, compressed with DEFLATE raw
and then encoded as Base64URL without padding. `d1.` versions the compression independently of the JSON
schema. Native `CompressionStream` and `DecompressionStream` perform compression without another library.
Their API does not expose a compression level. Compression preserves the exact compact JSON text; it does
not parse or reserialize that text. The format compaction preserves the semantic state, rather than the
whitespace or number spelling of an earlier JSON representation.

Compact versions 1 and 2 behind `d1.` are accepted. Version 1 links retain their previous expanded defaults
and expansion behaviour; version 2 uses collapsed defaults and supports the outgoing group opener.
Interface references require semantic identity because upstream IDs can be reused. Links without a viewport start with a fitted view. Unsupported or malformed compressed links fail with the same
restoration message as a failed replay. The documentation remains usable.

The complete URL may contain at most 16,384 characters. The decoded compact JSON is limited to 256 KiB,
enforced while streaming decompression and before parsing. A replay contains at most 2,048 steps. These
limits are independent of the newest 100 undoable steps retained locally. Exceeding a limit refuses the
link with a visible message; no steps are truncated. Many repeated actions compress well, so the URL length
alone would no longer bound the work of restoration.

Base64URL and DEFLATE are reversible, not hashing or encryption. The link contains the step sequence, so a
recipient could reconstruct intermediate semantic states by replaying shorter prefixes. The viewer currently
starts with empty undo history after opening a link. No server-side link storage or personal workspace is
used. The fragment is part of the link, so anyone receiving it can read its semantic state.

Interface targets carry their upstream key and their semantic identity: representation, interface type,
FQN, message type and variant, or HTTP provider, method and path. Resolution requires exactly one matching
semantic identity on the current graph. Changing or reusing the key cannot redirect a link to another
interface. Group targets use their system, component and `groupKey`; legacy groups without that key use
their trigger's semantic identity. An individual reaction additionally matches
its trigger, sorted action-interface identities, reaction model version and status within that group. Frequencies
and mutable reaction IDs are excluded from durable semantic references. If reactions in the same group have
identical signatures, a reference additionally records the concrete reaction key and the published snapshot
hash. Such a reference is valid only on that exact snapshot and still validates the complete signature.
Indistinguishable standalone groups and reactions without a published snapshot hash cannot be shared individually.

A malformed or unsupported format, a different site or environment, a missing entry or target, an ambiguous
match or an inapplicable step displays **This view cannot be restored on the current graph.** No partially
replayed view is published: the ordinary page entry remains usable. Changed frequencies and reaction IDs
alone do not invalidate a unique semantic target. Changed interface IDs are handled the same way. Changed
graph contents can invalidate a link; links carry
no historical snapshot and promise no best-effort recovery.

Sharing an unidentifiable entry or target reports **This view cannot be shared: an entry or target is not
uniquely identifiable on the current graph.** Back can undo the action that introduced it. Restoration
failures appear only on the addressed viewer; undecodable links produce one notice per page.
Messages appear beside the toolbar: failures to generate a link (automatically or through Share) are yellow
warnings; failures to restore an opened link remain red. Each message has a dismiss button; closing a
message does not change the graph. A later failed operation may display a new message. Opening a shared link scrolls directly to its viewer
below the navigation bar and activates it even when it starts below the fold. Other viewers retain their
ordinary entry views. Nearby preceding viewers finish replacing their fallback tables before that one
scroll, so their height changes do not move the target afterwards. Scrolling afterwards is unrestricted; automatic URL updates do not scroll the page.
Identity indexes are retained per graph snapshot and compact semantic payloads per session, so pan and
zoom only replace the viewport tuple before compression.


## Search this graph

Every reaction viewer has **Search this graph**, marked with a magnifying-glass icon, to the left of Back and Reset, including prepared message,
component and system views. It searches messages by type and variant, components by name and system,
HTTP operations by provider/method/path, and generic interfaces by type/FQN. Results carry the available
system, variant and interface context; reactions, groups and systems are not separate search targets.

The scope is the displayed graph selection, including nodes outside the viewport and components represented
by collapsed groups. Explicitly hidden contents are excluded. Hide, restore and expansion update the
candidates; pan and zoom do not. Each viewer keeps its own search. Typing makes no additional graph request
and changes neither visibility, pins nor history. An empty query has no list; an unsuccessful query reports
**No matches in this graph.** Matching ignores surrounding whitespace and case, ranks exact names before
prefixes and substrings, and sorts each category alphabetically without dropping results. Arrow keys navigate,
Enter selects and Escape dismisses the scrollable list. The search is disabled while source view is open
or the graph renderer is not ready.

Selecting a result centres the existing node and briefly highlights it in yellow. A component result locates
its visible groups together. Zoom adjusts to make the target readable and fit it into the viewport, respecting
reduced-motion preferences. Below the normal minimum zoom, the small viewport remains the lower bound so
wheel and pinch zoom-out do not jump inward. The normal minimum returns once the reader zooms back into
the normal range. Repeated selections replace the previous highlight. Expanded contents, node positions,
pins, semantic history and the Reset baseline stay unchanged: local search is a presentation action.
The usual automatic URL update retains the new viewport. Opening a new entry view remains the job of the
Explorer's global **Find an entry** tab.

## Standalone Reaction Graph Explorer

The graph icon to the right of the header search shows **Reaction Graph Explorer** on hover or keyboard focus. The mobile menu keeps
the full label, and the footer provides a **Reaction Graph Explorer** link under **Documentation**. Both entries
open `/reactions/` in the current environment, for example
`/dev/reactions/` or `/site/handbook/dev/reactions/`. It is built with the shell that publishes that
site/environment's `reaction-graph.json`; system parts link back to that shell. The environment ID
`reactions` is reserved on every site, so the Explorer path does not depend on environment names.
Sites without environments have no Explorer route or navigation item. The same applies to environments
without a configured Reaction Observer, including every environment when `jeap.doc.reactions.enabled` is false.
The system documentation writer contributes
`reactionEnvironments` to `site.json` only when at least one environment imports reactions; routes and both
navigation links use this list as eligibility. Navigation also checks the published shell's small
`reaction-explorer.json` marker and the Explorer route itself. A link appears only when a snapshot and its
page have been published together, even if a system part was rebuilt before the shell or the shell build
failed. Before the first snapshot, the link remains hidden. Navigation entries share checks and cache
successful results for ten minutes, negative results for one minute. This per-tab cache survives page
changes between system parts and is scoped to the site, environment and URLs. After expiry, the next
navigation checks again; focus and visibility changes trigger no requests. Publication changes can
therefore take up to the cache lifetime to appear on a subsequent navigation. Requests use the normal
HTTP cache policy. A published Explorer without a snapshot shows
**No reaction data has been published for this environment yet.** when opened directly. Observer
configuration is decided by `ReactionNetworkPublication`; build-time configuration alone never advertises
an unpublished Explorer.

Node menus link to component runtime views, events, commands and REST documentation in the current site
and environment. The shell includes a shared destination catalogue in `reaction-graph.json`, built from
the same architecture snapshot used for its pages. The catalogue is part of the shell's content digest,
so adding or renaming a component or interface page referenced by it triggers a shell rebuild even when
the reactions and systems index are unchanged. Structure templates supply the URLs; the Explorer does
not invent paths or duplicate the catalogue in each embedded viewer. Distinct destinations from different
templates appear as separate links labelled with their template IDs. Unknown or ambiguous destinations
within one template remain unavailable. Clipboard fallback and fullscreen availability hints use neutral,
temporary feedback. Invalid shared links and actual restore/share failures retain the red error style
and take precedence over copy confirmations.

The entry search covers the complete published graph, including content outside the current view. It
matches message type and variant, component name and system, HTTP provider/method/path, and generic
interface type/FQN. The displayed label is also searchable, including the identity of an interface whose
type or details are unknown. Matching ignores case and surrounding whitespace, ranks exact names before prefixes
and substrings, and keeps all results reachable in a scrollable list. Arrow keys navigate, Enter selects,
and Escape dismisses results. Labels retain system, variant and interface context and render as text.
Typing changes neither the graph nor its pins and makes no additional network request. The initial page
allocates no graph canvas. An interface selection pins that interface and displays its incident reaction
groups. Both interface and component selections start with collapsed groups. Components without a known system are not offered as
component results; their interfaces remain searchable. Selection replaces the Reset baseline; Back restores
the preceding selection and baseline. If Reset returns to the empty Explorer, history controls remain
available while there are undoable steps. Back restores the previous graph and viewport.
Shared links also restore an Explorer selection from its empty start.

The **Find an entry** and **Find paths** tabs keep both searches on the Explorer page. Switching tabs
preserves the search drafts and selected endpoints without changing the displayed graph. Arrow keys,
Home and End navigate the tabs. **Find paths** is available only in this Explorer. Its visible search field
filters eligible unselected interfaces using the existing directed reachability rules. Selected endpoints
remain visible as numbered chips with Remove buttons usable by mouse or keyboard; **Clear selection**
starts a new selection. Hints explain the next step, and **Show paths** applies the selection to the graph.
An interface's **Find paths from here** menu item opens this tab with that interface selected. Opening a
shared path link also selects the path tab and restores its endpoints. At least two connected endpoints
are required. Reactions and groups
are never selectable endpoints. The view uses the existing path algorithm, including directed cycles and
excluding PREDECESSOR relationships. A failed search says **No matches.** and changes no selection.

Entry search and path selection share the matching and keyboard component. The matching model requires
an explicit `graph` or `selection` scope; selection scope receives the visible IDs and excludes hidden
content. **Search this graph** uses selection scope, while **Find an entry** uses the complete published graph.

## Related

- [Generating the documentation](generation.md) - builds and publication
- [Configuration](configuration.md#the-reactions) - observers and environments
- [Architecture import](architecture-import.md) - snapshot replication
