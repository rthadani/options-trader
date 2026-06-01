---
name: option-chain
description: Read an option chain — interpret OI, volume, IV per strike, and pick liquid contracts.
---

When you call `fetch_option_chain` or `fetch_option_quote`, here's how
to read what comes back.

## Per-strike fields

- **delta** — directional exposure per share. ATM ≈ 0.50, drops with OTM.
  For a long call: 0.30 ≈ 30 delta long.
- **gamma** — rate of delta change. Highest near ATM, falls off either side.
- **theta** — $ decay per calendar day. Long options pay it; short collect.
- **vega** — $ change per 1-point IV move. Long IV → long vega.
- **IV** — implied vol for THIS specific contract. Compare to the
  underlying's `iv_rank_252d` to see if the strike is rich or cheap.
- **OI** — open interest. Liquidity signal; high OI = institutional flow.
- **volume** — today's trades. Spikes are interesting (someone took a view).

## Picking a tradable contract

1. **Liquidity floor:** bid/ask spread < 5% of mid, OI > 100, volume > 0.
2. **Strike choice:** for directional debit trades, 30–50 delta is the
   sweet spot — enough leverage without being lottery-ticket OTM.
3. **DTE:** 45–60 for long debit, 30–45 for short credit, 7–21 for tactical
   theta plays around catalysts.

## Reading the surface

- A skew toward put IV >> call IV means the market is paying up for downside
  insurance. Bullish bets look relatively cheap.
- High OI clustered at a round strike often acts as a magnet/resistance.
- A spike in volume on a single far-OTM strike with no news = unusual
  options activity, worth investigating.
