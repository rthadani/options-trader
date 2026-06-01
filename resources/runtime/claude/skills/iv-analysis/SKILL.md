---
name: iv-analysis
description: Interpret iv_rank, iv_percentile, iv_minus_hv from get_indicators to assess option richness.
---

When `get_indicators` returns IV-related columns, here's what they mean
and how to use them.

## The columns

- `iv_rank_252d` — current IV's percentile rank across the last 252 trading
  days. 0% = lowest IV in a year; 100% = highest. **Use this for "is IV
  rich or cheap right now?"**
- `iv_percentile_252d` — same window, but the fraction of days IV was
  *below* today. Subtly different from rank; rank can be skewed by extremes.
- `iv_rank_window_used` — which window was used. 252d preferred but the
  engine falls back to 126d or 63d for newer symbols.
- `iv_minus_hv` — implied minus realized vol. **Positive = market is
  pricing more vol than the stock has actually realized.** Persistent
  positive iv_minus_hv is the seller's edge.

## Decision rules

- **iv_rank > 70%** → IV is rich. Favor *short-vega* structures: credit
  spreads, iron condors, covered calls. Avoid long premium unless the
  catalyst justifies it.
- **iv_rank < 30%** → IV is cheap. Favor *long-vega* structures: long
  calls/puts, debit spreads, straddles for vol expansion plays.
- **iv_rank 30–70%** → no strong vol view. Direction matters more than
  vol; use defined-risk debits to stay simple.
- **iv_minus_hv > 5** persistently → short-premium edge exists. The stock
  doesn't move as much as the market is pricing.

## Caveats

- Around earnings, IV always inflates. Don't compare iv_rank just before
  earnings to iv_rank in a quiet week — IV crush will normalize it.
- Single-stock IV can be skewed by recent vol events. Cross-check against
  sector peers if a number looks anomalous.
- iv_rank is descriptive, not predictive. High iv_rank says "the market
  thinks something might happen" — it doesn't tell you which direction.
