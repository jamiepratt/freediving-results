# CMAS Microplus census, 1 October 2026

The CMAS freediving results index linked to four timing competitions absent from
the retained #55 evidence snapshot: Caribbean Cup (28), Nordic Cup (33),
Vertical Blue World Cup (34), and Mytikas World Cup (35). The 2025 Mytikas
championship (3) and 2026 Roatan championship (30) already have retained #8
sources; they were not reacquired in this run. The separate CMAS masters
`/event-detail/4` link remains stale, as documented in the earlier
[timing acquisition](cmas-timing-acquisition-20260925.md).

The public timing API returned competition metadata and document indexes for
all four competitions. The app's cumulative rankings view supplied 55 rows for
Caribbean Cup and 33 for Vertical Blue. Its schedule view supplied 20 units
each for Nordic Cup and Mytikas. All 92 scheduled unit result endpoints returned
HTTP 200. Collection used one request at a time per host, at least five seconds
between starts. The unit responses contain 490 transport rows, of which 211 are
identical repeats by competition and publisher `ResID` on grouped and individual
unit endpoints. This leaves 279 source positions: Caribbean 81, Nordic 92,
Vertical Blue 70, Mytikas 36. The repeated rows retain alternate exact JSON
pointers and response hashes. Seven unit arrays were empty.

The Nordic document index listed one eight-page results PDF. Its original bytes
were retained with a verified PDF signature. A later source-bound reconciliation
accounts for all 76 printed result rows, by page counts 11, 5, 16, 6, 12, 8,
13 and 5. Every printed row matches one official API result on name, country,
discipline, category, declared depth, raw result, penalty, final result, status
and note. Sixteen additional daily API rows have no counterpart in this final
PDF view. The printed final rank differs from the matching daily unit rank in
43 rows; both ranks remain separate. PDF medals are retained as printed.
The other three document indexes list no documents. Cumulative rankings are
separately classified as 88 aggregates, not sporting attempts. A matching
publisher result does not establish reviewed sporting-attempt identity.

Private originals, per-response receipts, the `cmas-microplus-private-census/v1`
packet and extended SQLite snapshot are under
`/Users/jamiep/.codex/private-corpora/issue55-cmas-microplus-20261001/`.
The initial packet SHA-256 was
`995edfa624624e0059ddf12e3a5be52a4c977cf672a38e390ffe1127e18dcfb2`.
Its snapshot extends the 28 September #55 v8 snapshot with 480 records:
105 sources, 279 API source positions, 88 aggregates and eight explicit gaps.
Initial snapshot SHA-256 is
`db196f7e5e96d971c06f157d9cd15a7c0e2a36cc85e3a3e23d46214ebcfab6b2`.
The retained Nordic reconciliation packet SHA-256 is
`6fe02528a2e744958b7428d9731ee96817363c76fe8a91d190998b5f653d98e9`.
The final CMAS packet v2 SHA-256 is
`15afe53f48be667209827d958bc1a0d844c9159bc6b8f5bead214f21554fd711`.
The `snapshot-v2/` extension from the same v8 base has 631 new records:
105 source records, 279 API positions, 76 PDF positions, 88 ranking aggregates,
76 cited PDF/API relationships and seven empty-unit gaps. Snapshot SHA-256 is
`278daebb34b6770cf52baf1cc412148f3384435c09fd113e5ab6267815bf6a71`.
Verification and byte-identical replay passed for both snapshot versions.

The registered retained JSON router now covers one source-backed Results route:
unit 3533 under competition 28, event 558. Its original response is 9,155 bytes,
SHA-256 `1fb994cd08c19eebfc9ffe1e757d8ee3ef7ccf0d1e94d4a2fbd797acdb94adf0`.
Its acquisition receipt and six packet positions at JSON pointers `/0` through
`/5` were checked against those bytes. The bridge requires the exact route,
receipt hash and length, supported row schema, and source-hash-bound pointer
citations before claiming positions. Identical replay merges duplicate inputs;
changed bytes require their own matching receipt and remain a separate version.
This narrow route does not generalize to the other Microplus unit endpoints or
establish six distinct sporting attempts.
