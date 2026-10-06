# Private retained AIDA comparison

`scripts/retained_aida_diff.py` compares the complete retained June 3 women DNF
positions against both pinned AIDA HTML extraction artifacts. It checks every
input in the existing private packet's `hashes.json`, reads existing EDN using
Clojure's safe EDN reader, and verifies the import manifest, complete original
table, raw cells/HTML, coordinates, observation references and selected ledger.
It does not run a new parser or import observations.

```sh
python3 scripts/retained_aida_diff.py \
  --hashes "$private_packet/hashes.json" \
  --hashes-sha256 "$verified_hashes_sha" \
  --output-dir "$private_output"
```

The deterministic JSON accounts for all 209 original positions and 418 retained
versions, including the selected 103 women positions and 206 versions. Every
selected position retains all raw and parsed fields, unknown/null values,
literal zero Points, status, source byte/line, candidate and extraction IDs,
parser and artifact pins. Differences include type and missing-versus-null
changes. Repeated identical artifacts remain separate extraction versions;
their order never establishes publisher revision order or distinct dives.

The escaped HTML derivative displays all selected rows and complete version
payloads. Original scripts, links and remote assets are escaped text. It uses
only the existing owner stylesheet. No later exact final source is currently
retained, so the report explicitly records missing final-source history. Source
RP, Points and observed arithmetic establish no post-penalty final distance.

## Authenticated owner access

`/owner-evidence/sporting/aida-diff` is linked from the sporting workspace. It
shares the existing owner gateway authentication and no-store response policy.
The route accepts no source selector or query and safely renders independently
pinned JSON. It creates no extraction review, sporting, relationship or
publication event. The recorded unreviewed statuses describe the retained
artifacts; current accuracy states remain in the live sporting proof cards.

Use a separate private configuration attached through
`OWNER_EVIDENCE_AIDA_DIFF_CONFIG`. Preserve frozen sporting, owner, source bundle
and comparison configurations. Configuration shape:

```json
{
  "schema": "retained-aida-diff-service/v1",
  "report": {"path": "/absolute/private/report.json", "sha256": "REPORT_SHA256"},
  "source_sha256": "ORIGINAL_SHA256",
  "artifact_sha256s": ["ARTIFACT_A_SHA256", "ARTIFACT_B_SHA256"],
  "hashes_sha256": "INPUT_PINS_SHA256",
  "selected_positions": 103,
  "source_positions": 209
}
```

The configuration and report must use existing private file ownership and
permissions, never symlinks. Startup verifies them; each read rechecks the exact
report hash, source/artifact/input pins, row counts and absence of authority.
Missing or altered files return 503; query selectors return 400. Keep both
payloads outside Git and stage them separately through the guarded private
activation. The original publisher HTML remains restricted.
