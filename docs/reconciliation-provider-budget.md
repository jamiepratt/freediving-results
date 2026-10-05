# Private Jev trial budget

The reconciliation flow reserves a cumulative provider budget at its actual
`execute!` boundary. A remote HTTPS Jev run requires `:provider-budget-path`.
The path names a private, absolute EDN ledger. The caller also supplies
`:provider-pricing`. A missing baseline, unsupported model, incomplete pricing,
or exhausted budget prevents dispatch. Local synthetic runs can use the same
gate with a stand-in `execute!` and no credentials.

Initialize the ledger exactly once with
`freediving.reconciliation-budget/initialize!`. Its historical reservation
vector must account for all identifiable prior attempts in the bounded trial.
Each entry needs `:type :reservation`, a unique `:id`, `:historical? true`,
`:request-hash`, `:source-sha256`, `:price-version`, and conservative
`:reserved-usd`. Keep source receipts private. An uncertain prior attempt stays
reserved. The initializer refuses an existing ledger, so changing a batch or
spec cannot reset accumulated calls. An incomplete prior history is a preflight
blocker, not a reason to start from zero.

The gate allows fewer than 15,000 total reservations and reserves before each
request. It rejects a request when prior reservations plus its estimate would
reach or exceed $10. A reservation is never refunded after timeout, crash,
invalid response, or retry. Cache and deterministic outcomes do not reserve.
File locking and atomic replacement serialize concurrent reservations across
processes. The private ledger contains its own SHA-256 envelope and an
append-only event sequence. Provider-reported token usage is appended after a
response, while an absent receipt remains explicitly unknown.

Pricing is pinned per reservation, so later policy changes preserve historical
estimates. The supported model is versioned `jev-1.13.0`. The configured input
rate cannot be below the published $0.042 per million tokens, and both input
and output token upper bounds must be at least 64,000. The reservation prices
the full input and output bounds, including provider-side framing rather than
relying on JSON byte size. The published output rate is zero; a nonzero
account-specific rate can be configured. The source, rate version and all rate
fields are retained with each reservation. See the
[provider model documentation](https://docs.typesafe.ai/models) for published
rates and token limits, and the
[provider API documentation](https://docs.typesafe.ai/api) for response usage.
Account-specific terms may differ, so the operator must pin a conservative
rate before dispatch. Reported usage and reservation estimates are not settled
bills. Actual billed cost remains unknown without settled provider evidence.
