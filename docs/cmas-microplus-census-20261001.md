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
were retained with a verified PDF signature. PDF positions have not been
transcribed or reconciled with API rows. The other three document indexes list
no documents. Cumulative rankings are separately classified as 88 aggregates,
not sporting attempts. Source relationships outside byte-identical API rows,
and confirmed distinct attempt counts, remain unknown.

Private originals, per-response receipts, the `cmas-microplus-private-census/v1`
packet and extended SQLite snapshot are under
`/Users/jamiep/.codex/private-corpora/issue55-cmas-microplus-20261001/`.
Packet SHA-256 is
`995edfa624624e0059ddf12e3a5be52a4c977cf672a38e390ffe1127e18dcfb2`.
The snapshot extends the 28 September #55 v8 snapshot with 480 records:
105 sources, 279 source positions, 88 aggregates and eight explicit gaps.
Snapshot SHA-256 is
`db196f7e5e96d971c06f157d9cd15a7c0e2a36cc85e3a3e23d46214ebcfab6b2`.
`scripts/unified_evidence_snapshot.py verify` and `replay` both passed.
