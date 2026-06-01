# options-trader

A Clojure terminal app for options research and execution. It talks to
Interactive Brokers, computes technical/fundamental indicators on top of a
local DuckDB warehouse, lets you screen the universe with raw SQL or natural
language, and hands the heavy lifting to **claude** (or **pi**) as an in-app
research agent that calls MCP tools to fetch news, filings, fundamentals, IV
chains, and so on.

Three things make it different from a generic dashboard:

1. **No DSL.** Screens are SQL or a natural-language description. The agent
   gets the schema and writes the SQL.
2. **Self-extending schema.** When a screen description mentions a column
   that doesn't exist, the agent proposes an indicator spec and (with your
   `:confirm? true`) the engine adds the column + backfills history.
3. **The agent is the UI.** Slash commands like `/portfolio` and `/run` are
   thin wrappers that forward to the agent so it can use MCP tools and add
   context, instead of just dumping table rows.

---

## Quickstart

Prerequisites: **Java 21+**, **Clojure CLI**, **Babashka (`bb`)**, **DuckDB**
(bundled), **Interactive Brokers TWS or Gateway 10.43.02** running locally,
the **`claude`** CLI installed and logged in (Pro/Max plan or
`ANTHROPIC_API_KEY`). Optionally **`pi`** as a second agent. Optional but
recommended: set `EDGAR_USER_AGENT="<your name> <your-email@example.com>"`
so SEC filings fetches identify themselves correctly.

```bash
# One-time setup. Checks prereqs, seeds the per-user config dir
# (CLAUDE.md overlay, indicators.edn, screens, runtime-claude/{settings.json,
# agents/, skills/}, runtime-pi/mcp.json), and runs DB migrations.
bb install

# Launch the TUI. Prints which config root it's using, and refuses to
# launch (with an install hint) if you haven't run `bb install` yet.
bb run-tui

# Use a different config root for this run only:
OPTIONS_TRADER_CONFIG_DIR=/tmp/sandbox bb install   # seed there
OPTIONS_TRADER_CONFIG_DIR=/tmp/sandbox bb run-tui   # launch against it

# Bypass the wrapper:
clojure -M:run

# Headless refresh tasks (cron-safe via bin/refresh):
clojure -M:cli refresh-daily        # bars (daily) + indicator recompute
clojure -M:cli refresh-intraday     # bars (intraday)
clojure -M:cli refresh-news         # news headlines + sentiment
clojure -M:cli refresh-fundamentals # SEC EDGAR fundamentals + ratios
clojure -M:cli refresh-filings      # 10-K/Q/8-K/13D/G filings list
clojure -M:cli refresh-universes    # S&P 500 / NDX / Mag7 membership
clojure -M:cli refresh-portfolio    # positions + account summary

bin/refresh daily                   # cron wrapper (flocked, logged)
```

`bb install` is idempotent — running it again seeds anything new that
ships with an upgrade without overwriting files you've edited.

---

## Where things live

The TUI is fully standalone: it spawns `claude` and `pi` with isolated
config directories so your personal `~/.claude` and `~/.pi` are never read
or touched.

```
~/.config/options-trader/                      ← per-user config root
├── options_trader.duckdb                      the DuckDB warehouse
├── CLAUDE.md                                  your personal overlay
│                                              (loaded as the middle layer
│                                              of the agent's system prompt)
├── indicators.edn                             YOUR indicator specs
├── screens/                                   YOUR .screen files
├── runtime-claude/                            CLAUDE_CONFIG_DIR for claude
│   ├── settings.json                          MCP server + permissions
│   ├── agents/                                sub-agent definitions
│   │   ├── options-strategist.md
│   │   ├── earnings-preview.md
│   │   └── position-risk.md
│   ├── skills/                                skill definitions
│   │   ├── option-chain/SKILL.md
│   │   ├── filings-research/SKILL.md
│   │   └── iv-analysis/SKILL.md
│   └── projects/                              per-session history
└── runtime-pi/                                pi's isolated config
    ├── mcp.json                               same MCP server, for pi
    └── sessions/                              per-session storage
```

Override the root with `OPTIONS_TRADER_CONFIG_DIR=/some/path` (e.g. for
tests or alternate installs). On macOS the default is `~/Library/Application
Support/options-trader/` instead of `~/.config/...`.

---

## Slash commands

In the TUI, anything starting with `/` is a command. Anything else is a
message to the active agent.

### TUI-local (never touch the agent)

| Command | What it does |
|---|---|
| `/quit` `/exit` `/q` | Exit the TUI |
| `/help` | Print this list |
| `/connect` `/disconnect` | TWS connection lifecycle |
| `/refresh` | Re-read positions + account summary from the DB |
| `/reload-screens` | Rescan `<config>/screens/` into the screens table |
| `/reload-indicators` | Validate `<config>/indicators.edn` |
| `/model [provider:id]` | Show or switch model. `/model claude:claude-opus-4-5`, `/model ollama:qwen2.5-coder`, `/model kimi:kimi-k2.5` |
| `/agent [pi\|claude]` | Switch active agent |
| `/clear-investigation` | Return to the `:scratch` scope |
| `/reset` | Drop both claude and pi session ids for the current scope (forces fresh threads next message) |
| `/sessions` | List tracked scope → session bindings with token counts |

### Hybrid (TUI state + agent kick-off)

| Command | What it does |
|---|---|
| `/investigate <SYM>` | Set scope to `<SYM>` **and** ask the agent for a quick overview using `portfolio_summary`, `fetch_news`, `fetch_filings`, `get_indicators` |

### Agent-routed (forwarded to claude/pi; uses MCP tools)

| Command | Becomes | MCP tool |
|---|---|---|
| `/portfolio` | "Show me my portfolio summary using the portfolio_summary tool." | `portfolio_summary` |
| `/screens` | "List the screens available using the list_screens tool." | `list_screens` |
| `/run <screen> [args]` | "Run the '<screen>' screen using the run_screen tool…" | `run_screen` |

Everything else you type (no leading slash) is sent verbatim to the active
agent, which can reach for any of the 18 MCP tools (see below).

---

## How the chat works

The agent is **conversational, scoped per investigation, and persists
across restarts**:

- Each `:scope` (the default `:scratch`, or a ticker after `/investigate AAPL`)
  has its own claude session id and its own pi session id.
- Every message resumes that session (`--resume <id>` for claude,
  `--session-id <id>` for pi). The subprocess is fresh per send; the
  conversation is continuous because the agent CLI restores its history
  from on-disk session storage.
- Scope → session bindings are persisted to disk (`scope-sessions.edn`),
  so threads survive TUI restarts.
- `/reset` drops the current scope's session ids (token counters survive).
- `/clear-investigation` returns to `:scratch` (other scopes untouched).

To carry parallel research threads, just `/investigate <SYM>` between them.

---

## Doing research

The agent has 18 MCP tools and the runtime system prompt
(`resources/runtime/CLAUDE.md`) telling it when to reach for each. Typical
flow:

1. **Set scope:** `/investigate AAPL`
2. The hybrid handler tells the agent the scope changed and asks for an
   overview — it'll call `portfolio_summary`, `fetch_news`, `fetch_filings`,
   `get_indicators` to surface anything notable.
3. Follow up in natural language: *"Pull the last four 10-Qs and tell me
   whether their gross margin is trending up,"* *"What's the IV rank vs.
   peers right now?"*, *"Is there activist 13D/G activity?"*
4. The agent picks the right tool. Tool calls show up in the right-hand
   activity column; the conversation stays on the left.

### MCP tools available

| Category | Tools |
|---|---|
| Portfolio | `portfolio_summary`, `place_order` (gated), `cancel_order` (gated) |
| Screening | `list_screens`, `run_screen`, `run_sql`, `get_indicators` |
| Market data | `fetch_option_chain`, `fetch_option_quote` |
| Filings (EDGAR) | `fetch_filings`, `fetch_filing_body`, `fetch_filing_item`, `fetch_xbrl_facts`, `fetch_corporate_actions` |
| Fundamentals | `fetch_fundamentals`, `fetch_earnings_history` |
| News / sentiment | `fetch_news` |
| Short interest | `fetch_short_interest` |

`run_sql` is the escape hatch: when no fetcher fits, the agent writes raw
DuckDB SQL against the warehouse. The `get_indicators` tool reads
`latest_indicators` for one or more symbols, so the agent can compare
across names without round-tripping through a screen.

---

## Customizing the agent

Three knobs, in increasing order of intrusiveness.

### 1. Personal overlay (`<config>/CLAUDE.md`)

Edit this file to inject preferences the agent should respect on **every**
turn — risk tolerance, preferred structures, accounts to focus on, things
to avoid. It loads as the middle layer of the system prompt, between the
packaged base prompt (`resources/runtime/CLAUDE.md` in the repo) and the
per-spawn dynamic block. Empty file = no overlay applied.

### 2. Sub-agents (`<config>/runtime-claude/agents/<name>.md`)

A sub-agent is a markdown file with YAML front-matter:

```markdown
---
name: my-screener
description: Pick a screen given an outlook. Invoke when the user says "find me X".
---

You are a screener. Steps:
1. list_screens to see what's available.
2. Match the user's outlook to the closest screen by tag.
3. run_screen and return the symbols.
```

Claude auto-discovers everything in `<runtime-claude>/agents/` because
`CLAUDE_CONFIG_DIR` points there. Three starters are seeded by `bb install`:

- **options-strategist** — pick a structure given outlook + IV + horizon.
- **earnings-preview** — build a pre-print briefing for a single ticker.
- **position-risk** — audit the book for concentration, greeks, expiry
  clustering.

### 3. Skills (`<config>/runtime-claude/skills/<name>/SKILL.md`)

A skill is a directory with a `SKILL.md`. Front-matter is the same shape
as agents. Skills are reference material the agent pulls in when relevant
(not standalone agents). Three starters:

- **option-chain** — how to read OI, volume, IV, greeks per strike.
- **filings-research** — order-of-operations for `fetch_filings` / `fetch_filing_body` / `fetch_filing_item`.
- **iv-analysis** — interpret `iv_rank`, `iv_percentile`, `iv_minus_hv`.

Skills are auto-discovered by claude (via `CLAUDE_CONFIG_DIR`) **and**
explicitly handed to pi (`--skill <path>` for each subdir). So they work
across both agents.

To add your own: drop a directory under `<config>/runtime-claude/skills/`
with a `SKILL.md` inside. No restart needed — claude reads them at spawn
time. Same for agents under `<config>/runtime-claude/agents/`.

To ship new starters with the binary: drop them in
`resources/runtime/claude/{agents,skills}/` and append the name to the
corresponding `manifest.edn`.

---

## Creating indicators

Indicators are entries in `<config>/indicators.edn`. The engine reads this
file on every refresh and `ALTER TABLE ADD COLUMN IF NOT EXISTS` for any
new entry, then backfills history.

### Manual: edit the file

Open `~/.config/options-trader/indicators.edn` and append to `:indicators`:

```clojure
{:kind :RSI :params [21] :column :rsi_21 :percentile-windows [126 252]}
```

- `:kind` — one of the supported ta4j kinds (run
  `(options-trader.indicators.ta4j/known-kinds)` in a REPL to list).
- `:params` — constructor args in the same order ta4j expects (e.g.
  `[period]` for RSI, `[period k]` for BollingerBandWidth).
- `:column` — snake_case identifier; this becomes the DuckDB column name.
- `:percentile-windows` (optional) — extra columns like `rsi_21_pct_126d`
  computed from a rolling-window percentile rank.

Validate with `/reload-indicators` (TUI). Then run `/refresh` or
`clojure -M:cli refresh-daily` to actually add the column + backfill.

### Composite indicators

Composites combine other columns into boolean or scalar flags:

```clojure
{:kind :ttm-squeeze :column :ttm_squeeze_flag}
```

These live under `:composites` in the same file and are computed after the
base indicators on every refresh.

### Indicator from a screen (agent-driven)

If a description-driven screen references a column that doesn't exist, the
screener returns `{:missing <col>}`. The TUI routes that to a
`:propose-indicator` action — the LLM proposes a spec from
`ta4j/known-kinds`, you confirm with `:add-indicator`, and the spec is
appended to your `indicators.edn`. The next refresh picks it up.

### Caveats

- Removing an entry from `indicators.edn` does **not** drop the column —
  backfilled history would be lost. Drops happen via `indicator_renames`
  or an explicit migration.
- Renames keep the old column for the deprecation window.

---

## Creating screens

Screens are `.screen` files in `<config>/screens/`. Each file has YAML
front-matter and either a SQL body OR a natural-language description.

### SQL body

```
---
name: Tight Squeeze High ADX
description: Bollinger inside Keltner with strong trend
universe: latest_indicators
tags: [squeeze, trend, momentum]
---
SELECT symbol FROM latest_indicators
WHERE ttm_squeeze_flag = true
  AND adx_14 > 25
  AND rsi_14 BETWEEN 45 AND 65
ORDER BY adx_14 DESC LIMIT 25
```

### Description only (the agent writes the SQL)

```
---
name: Cheap GARP
description: Cheap on FCF yield + low leverage + accelerating earnings growth
universe: latest_indicators
tags: [value, growth, garp]
---
```

The first run generates SQL from the description via the agent and caches
it on the row. Subsequent runs reuse the cache until the description text
changes (then the cache invalidates).

### Workflow

1. Drop the file in `~/.config/options-trader/screens/` (or edit one of the
   shipped starters there — they're seeded on first launch).
2. The watcher upserts the screen to the `screens` table within ~250 ms.
3. Run it: `/run Cheap GARP` in the TUI, or `clojure -M:cli screen "Cheap GARP"`.

If the watcher missed the file (e.g. you copied it in while the TUI wasn't
running), `/reload-screens` does a manual rescan.

### Shipping new starter screens

If you're contributing screens that should ship with the binary, put the
file in `resources/screens/` and append its base name to
`resources/screens/manifest.edn`. Init/launch will seed it into each
user's config dir (without overwriting any local edits).

---

## Stack

| Layer | Library |
|---|---|
| Language | Clojure 1.12 |
| Broker | `ib-re-actor-976-plus` 0.1.10.43.02 + TWS 10.43.02 (PINNED) |
| Indicators | `ta4j-core` 0.16 (reflection-free Java interop) |
| Database | DuckDB JDBC 1.1.3 + `next.jdbc` + HoneySQL |
| TUI | charm.clj |
| Filings | edgarjure |
| Agent | `claude` CLI subprocess + native MCP stdio server (`clojure -M:mcp-server`) |
| Async seams | `core.async` (TUI loop, IB pacer, P&L mult) |

See `CLAUDE.md` for the source-layout breakdown and architectural
invariants (single DuckDB writer, callback-shaped IB layer, self-healing
refresh, action guards, etc.). The runtime CLAUDE.md at
`resources/runtime/CLAUDE.md` is the system prompt loaded into the
in-session agent — separate from the repo CLAUDE.md.

---

## Troubleshooting

**`filings :unavailable` from `fetch_filings`** — SEC requires a User-Agent.
Set `EDGAR_USER_AGENT="<name> <email>"` in your shell, or edit
`resources/config.edn` and re-init.

**`TWS unavailable` from CLI** — IB Gateway or TWS not running, or wrong
port. Paper trading defaults to 7497; live to 7496. Check `resources/config.edn`.

**Migration fails on startup** — the TUI refuses to start on an unmigrated
schema. Check the printed error; if your local DB is from an older version
that's incompatible, back it up and re-run `clojure -M:run init`.

**Tests** — `clojure -M:test` runs the kaocha suite. All tests should pass
on a clean checkout.

**`/refresh-*` doesn't pick up edits to `indicators.edn`** — the engine
reads the file on every refresh, so a fresh `clojure -M:cli refresh-daily`
should see new entries. If the TUI's `/reload-indicators` says it parsed
OK but `/refresh` doesn't add the column, run the CLI refresh directly to
get clearer error output.
