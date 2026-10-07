# Six mid-August 2024 event views, 7 October 2026

This bounded [#195](https://github.com/jamiepratt/freediving-results/issues/195)
pass retains six calendar leads' observed Results dates and every observed
ranking filter. Cutoff: **2026-10-07T01:51:30Z**, 7 October Warsaw. B38 calendar
page 7 cards 19/20 and page 8 cards 4/5/6/7 bind the exact URLs, dates and
calendar types. All six Results expose only active `day_1`; no additional date
or pagination was observed. Five legacy Details print matching start/end dates
and matching calendar types. California's modern EventPage has no printed
Event Type field: its type remains unknown there, with calendar POOL retained
separately. Schedule and active Results date agree. Titles do not establish
formal category or finality.

| Primary Results | Date / calendar type | Printed disciplines | Positions / private versions | Cells / representations |
| --- | --- | --- | ---: | ---: |
| [Go The Distance California 4168](https://www.aidainternational.org/EventPage/4168) | 2024-08-17 / POOL | STA 1, DYNB 3, DNF 2, DYN 1 | 7 / 7 | 77 / 308 |
| [Funstatic 4243](https://www.aidainternational.org/Events/EventResults-4243) | 2024-08-17 / POOL | STA 10 | 10 / 10 | 120 / 480 |
| [Double K Cebu 5, 4255](https://www.aidainternational.org/Events/EventResults-4255) | 2024-08-12 / DEPTH | CNF 2, CWTB 4, FIM 3, CWT 2 | 11 / 11 | 132 / 528 |
| [Double K Cebu 4, 4252](https://www.aidainternational.org/Events/EventResults-4252) | 2024-08-11 / DEPTH | CWT 4, CNF 1, CWTB 4, FIM 3 | 12 / 12 | 144 / 576 |
| [Happdivers Cebu 3, 4249](https://www.aidainternational.org/Events/EventResults-4249) | 2024-08-10 / DEPTH | FIM 1, CWTB 3, CWT 3, CNF 1 | 8 / 8 | 96 / 384 |
| [Getecstatic Olympiad 4254](https://www.aidainternational.org/Events/EventResults-4254) | 2024-08-10 / POOL | STA 8 | 8 / 8 | 96 / 384 |

Primary tables retain **56 positions/private versions, 665 cells and 2,660
HTML/raw-text/decoded/value representations**. California has 11 columns;
the five legacy sources have 12. All parse and remain unreviewed; unresolved,
quarantine, imports and reviews are zero. Primary cards: 35 WHITE, 11 RED and
10 YELLOW. Twelve zero Points include one YELLOW. The 22 blank cells are Line
cells in Funstatic and Double K 4. No NR/CR suffix occurs in these checked
Results; predecessor NR/CR discrepancies remain preserved. Remarks, geography,
escaped names, zeros, blanks and pool/depth AP/RP units remain literal.
Numeric penalty, category, finality, current accuracy and distinct sporting
attempts remain unknown.

California exposes genuine [Results](https://www.aidainternational.org/StartList/4168#start)
and [Pre. Results](https://www.aidainternational.org/StartList/4168?day_index=0)
links. Both retained sources show active 2024-08-17 and seven rows. The two
acquisitions share one byte object and seven immutable observation IDs. Separate
private stages retain **14 supporting position/version instances, 154 cells
and 616 representations**. Their 154 normalized field values match the
EventPage rows; each raw HTML, raw text and decoded representation matches
28/154 fields, with 126 formatting differences per representation. No field
is overwritten and no sporting equivalence is inferred. The Pre. Results
label does not establish finality. Combined staging totals **70 position/version
instances and 63 unique observation IDs**, with 819 cells/3,276 representations.
These are evidence counts, not a count of sporting attempts.

The actual StartList sources demonstrated a recognition gap: numeric modern
`/StartList/[0-9]+` URLs use a single empty-ID `table.table__data[id=""]` with
`tbody#body_ajax`. Public packet and stage CLI tests first failed on the URL,
then failed on this actual table shape. The bounded adapter now accepts that
observed source family, retaining truthful source/row table selectors. Wrong
route/origin, wrong class, duplicate empty-ID table, wrong active date, receipt
binding, header/width and hash guards remain. All **24 relevant tests pass**.
Existing EventPage/EventResults extraction and version outputs remain unchanged;
parser `historical-aida-selected-html/v1` remains stable for predecessor replay.
Recognition of a Results-bearing StartList is not a claim that every StartList
is a result or that every source view is complete.

| Rankings | Actual options plus OVERALL | POST submissions | Effective views / byte objects | Aggregate positions | Acquired rows |
| --- | --- | ---: | ---: | ---: | ---: |
| [4168](https://www.aidainternational.org/EventRanking/4168) | DYN, DNF, STA, DYNB | 15 | 10 / 10 | 11 | 20 |
| [4243](https://www.aidainternational.org/Events/EventRanking-4243) | STA | 6 | 4 / 4 | 20 | 33 |
| [4255](https://www.aidainternational.org/Events/EventRanking-4255) | CWT, CNF, FIM, CWTB | 15 | 10 / 10 | 20 | 35 |
| [4252](https://www.aidainternational.org/Events/EventRanking-4252) | CWT, CNF, FIM, CWTB | 15 | 10 / 10 | 23 | 41 |
| [4249](https://www.aidainternational.org/Events/EventRanking-4249) | CWT, CNF, FIM, CWTB | 15 | 10 / 10 | 14 | 22 |
| [4254](https://www.aidainternational.org/Events/EventRanking-4254) | STA | 6 | 4 / 4 | 16 | 31 |

Every actual discipline option plus OVERALL was submitted with blank/Male/Female:
72 POSTs and six default GETs, 48 effective views/byte objects, 104 aggregate
positions. Acquisition totals: 182 rows, 1,260 cells and 5,040 representations,
including 11 empty acquisitions. Blank renders Male; defaults inherit
OVERALL/Female session state. Original tables account every reference row.
**430/430 common discipline fields match**. OVERALL retains 112 matching
present components, 164 absent component dashes and 96 reconciled printed total
rows. Its 896 name/country and 448 component representation flags remain
explicit. No country disagreement or multiple reference candidate occurs.
Equality is descriptive; rankings add zero attempt positions, versions or
relationships. Omitted fields remain absent. Prior ambiguity remains unchanged.

All **91 retained responses are HTTP 200 HTML**, representing **60 byte objects**:
six primary Results, one shared supporting StartList object, five legacy Details
context objects and 48 ranking objects. Six Details acquisitions include the
California EventPage primary Results; six Results-link acquisitions include its
supporting StartList, followed by one query acquisition and 78 ranking
acquisitions. The manifest adds 56 source IDs, including two supporting
acquisition contexts with identical source bytes.

Bodies are CDP-decoded/re-encoded UTF-8, not original compressed wire bytes.
All initial request, request and response clocks are retained. The 72 original
POST methods, wire payloads and request clocks remain separately bound to HTTP
302 responses and following GET request/response clocks. Redirect entries'
request clocks denote following GETs. Capture-completion clocks are separate;
rendered metadata has no exact separate clock, explicitly null with reason.
Immediate rendered selectors sometimes reported empty rows/headers while the
retained original contained a table. Those exports remain literal and qualified;
raw original table scope is independently accounted. No rendered completeness
claim follows those transient exports. No standalone DOM source is retained.
The initial California navigation preceded capture; missing initial body/clocks
remain explicit, with a captured reload retained. Paging cursors, hasMore and
truncation flags remain recorded. Full event streams, cached widgets and
subresource bodies are excluded. Temporary internal browser closed; no service
started.

Cumulative: **72 events, 673 source IDs, 2,148 position/private-version instances,
2,141 unique observation IDs, 81 routes, 554 leads, 72 gaps and zero
relationships**. Typed resolutions total 85 attempt-results and 70
not-attempt-source; event parents remain unresolved. All prior 2,078
positions/versions, 540 lead IDs and source/receipt/stage bindings remain intact
through 24 immutable corpus references, with no recursive corpus copies.

Worker verification runs **95 actual CLI checks**: eight exact original replays,
32 writable controls, 32 semantic rejections, validate, 20 lead queries, gate
and ingest refusal. Tampering covers source bytes with rehashed receipt,
receipt date, staged field with rehashed payload, and original selected date
changed to 2023 with rebound receipt/expected hash. No permission-only refusal
or setup failure counts as semantic proof. An additional post-correction
manifest validation passes. Main independently verifies eight new-stage
replays and 70 ordered IDs/63 unique IDs with 185 original body/receipt/stage
files unchanged in bytes, size and mtime. Six predecessor replays retain 172
ordered versions and exact original byte/mtime bindings. Main validate,
eight session/source queries, gate and ingest checks pass. Ordinary 2023 gate
remains **closed: 635 blockers**, comprising 79 routes, 399 nonresolved leads,
85 unknown-category checks and 72 census gaps. Ingest refuses before opening
a nonexistent candidate or creating output. Legacy importers are not universally
intercepted. No Clojure edits occurred.

Private corpus:
`/Users/jamiep/.codex/private-corpora/issue195-b62-six-midaugust-events-2024/`.
Independent audit and main checks are outside the corpus in
`/Users/jamiep/.codex/private-corpora/issue195-b62-independent-audit/` and
`/Users/jamiep/.codex/private-corpora/issue195-b62-main-verification/`.

Production, deployment, database, owner-authority, public, identity,
relationship, VPN and paid-API changes are zero; no fresh live certification.
Full #195 remains open for wider 2024 archive/calendar/national/organizer scope
and genuine publication acceptance. Historical pilot acceptance depends on
#194's genuinely reviewed first public DNF slice; public automatic same-attempt
acceptance requires genuinely owner-labelled held-out evidence. No deferred
threshold, exception or attestation was invented; private ADR 0003 is unchanged.
Future implementation scope remains in #195.

Main and both workers verify GPT-6.1 Sol/high, never, danger-full-access and
disabled unrestricted filesystem from runtime records. Network is enabled by
explicit context and successful transport; a separate rollout network policy
field is unexposed.

Preseal independent audit passes **160,493 checks, zero errors**, binding 387
inputs and 51 outputs. All **300 indexed files, 302 total files and 374 new
nodes** verify with 0700 directories, 0600 files and no symlinks. All 4,009
prior indexed files, 4,057 prior total files and 4,717 prior nodes remain
unchanged. Four new helper/inventory files initially had 0644 modes because
shell redirection preceded Python's private umask; these were normalized to
0600 before the approved audit. No source bytes changed. A preseal supporting
header-order correction and modern locator correction were independently
rechecked before audit freeze. Audit files froze before the worker's single
index/checkpoint seal; no corpus writes follow seal.

| Artifact | SHA-256 |
| --- | --- |
| Index | `fed775dadf49b97dce47804a35689ff08945d817859b7f2dc6e8af02cff4b669` |
| Checkpoint | `d27db586b64d52274bec53a8a6e9fcc8ef9194ac7dc8523231023cab90896ce0` |
| Manifest | `56a7e97d40cf955bb7c4dfecfcc93349e3b5f68934f8e6b0d7b9453afecf4a48` |
| Primary stage | `0cc1a27978063c7f2c08983e491232fdb8961a91a1b914a60d6c9fc9002b0526` |
| Supporting Results stage | `e114a5daaef593a851980ce748d331bfc5b70d94ef4601e16309dbdc1db04fe1` |
| Supporting day-index stage | `e7f2bc271da7113a26461320903f723e3cad476b1114d35738641a46e9f93cd0` |
| Comparison ledger | `fef92ad4a8c302800974b38a14896cb5517065cc652139dd841c838502dccd74` |
| Capture summary | `bc4ba83adec8e87c6a06024d8c0ea954b350c7b91a54b00163f325fe13074d69` |
| Worker CLI proof | `a74f218fbb2dc2a28f625f2ba20acb260bfdc232bde3c2194b2b8d47784a0995` |
| Frozen preseal independent audit | `34569d24bf6417275f3bff4a93aa720dde5c06c8ae381147e09ecde446a29391` |
| Main 19 CLI checks | `bffd1896015c63efd557a76b1707df3931950800781fe0599a11fead3e8fac08` |
| Main final manifest validation | `5364e2e63362be7c840d6eae7e47e24f357675f1155bb053400910f1ca4d0a44` |
| Main 24 tests | `ae14cb65b2aab1b36d9f17eee46da75a220a06cf343bcfaedc2eb6ea3ddc2670` |
| Main predecessor replay | `e8441aebb5b913f1dfaa47c0bc12159c8a41bfb286bf04dff5beb0ab8f0d85ad` |
| Main sealed map | `10949b3e85499caa9aeb8244588563ba02bb6fbd4c3c7e9050e57095e5ba512c` |
| Final independent summary | `068808aa44bb5808b0a858ef2be08e06207537990b1472b8f457b90c4446c8e9` |
| Final independent tree | `645914b80dc3aa7fe17973492fb97b4ad49b83895d68c44c8188e5bc9044d75d` |

Final read-only audit passes **167,481 checks, zero errors**: 160,493 preseal
and 6,988 final checks. All 438 frozen input/output bindings and new/old tree
bindings remain unchanged. Final audit reports use fresh external files;
postseal corpus writes, reseals and frozen external audit mutations are zero.
Main independently verifies the sealed tree, frozen audit bindings and every
prior indexed file. Verification setup failures involving a test-module import,
main post-replay variable and an old checkpoint-key assumption were corrected
before successful checks and are excluded from semantic guard proof. The two
actual source-recognition RED failures are retained separately above.
