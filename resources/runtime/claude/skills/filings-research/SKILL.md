---
name: filings-research
description: Use fetch_filings + fetch_filing_body + fetch_filing_item to research SEC filings effectively.
---

Order-of-operations for filing research:

## 1. List the filings

`fetch_filings` with `form_type` and a date range. Don't fetch all forms;
use the right one for the question:

- **10-K** — annual, the deep dive. Risk factors, MD&A, full financials.
- **10-Q** — quarterly, lighter. MD&A is where management commentary lives.
- **8-K** — material event. Time-sensitive: guidance changes, M&A, exec
  changes, litigation, earnings. Filter to the last 30–90 days.
- **13D / 13G** — activist or 5%+ ownership disclosure. 13D = active
  intent; 13G = passive. Surface these prominently.
- **DEF 14A** — proxy. Compensation, board, related-party transactions.

## 2. Drill in

For the deep read, `fetch_filing_body` with the accession id and
`format: "text"` (faster than HTML). For specific sections in 10-K/10-Q,
`fetch_filing_item` with the item id is more surgical:

- `1A` — Risk Factors (10-K only)
- `7` — MD&A
- `7A` — Quant/qual market risk
- `8` — Financial statements
- `9A` — Controls
- `15` — Exhibits

## 3. What to look for

- **Risk factors that are NEW vs. last year's 10-K.** Year-over-year diff
  is signal; copy-paste is noise.
- **MD&A tone changes** — "headwind", "softer demand", "delayed" creeping in.
- **Off-balance-sheet items** — supplier concentration, contingent
  liabilities, undisclosed customer dependencies.
- **Cite the filing date** when summarizing. A 10-K from 14 months ago
  describes a different company than today's.

## 4. Pair with other tools

- `fetch_xbrl_facts` for structured financial line-items keyed by concept.
- `fetch_corporate_actions` for splits/dividends/spinoffs the EDGAR feed
  doesn't surface cleanly.
