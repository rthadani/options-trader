# options-trader

Clojure TUI for options research: real-time market data, technical indicators,
fundamentals, news/filings, raw-SQL screening over a DuckDB warehouse, and
Claude Code as the in-app research agent. Interactive Brokers TWS API,
Lanterna terminal UI.

## Stack

| Layer        | Library / version                                |
|--------------|--------------------------------------------------|
| Language     | Clojure 1.12 (deps.edn)                          |
| Broker       | ib-re-actor-976-plus 0.1.10.43.02 (PINNED)       |
| TWS / Gateway| 10.43.02 (PINNED — do not bump)                  |
| Indicators   | ta4j-core 0.16 (reflection-free Java interop)    |
| DB           | DuckDB JDBC 1.1.3 + next.jdbc + HoneySQL         |
| TUI          | Lanterna 3.1.2                                   |
| Screener     | Raw SQL via next.jdbc; `.screen` files watched   |
| Filings      | edgarjure (10-K/Q, 8-K, 13D/G)                   |
| Async seams  | core.async (TUI loop, pacer, P&L mult)           |
| Agent        | `claude` CLI subprocess + native MCP stdio server|

## Source layout

```
src/options_trader/
  core.clj                  -main; init wizard; --check-config; --headless
  cli.clj                   Headless dispatch (refresh-*, screen, portfolio, …)
  logging.clj               timbre setup (file rotation; stderr stays MCP-only)
  actions/
    core.clj                handle-action multimethod (the funnel)
    research.clj            :research/fetch-* handlers (news, filings, fundamentals, …)
    indicators.clj          :propose-indicator / :add-indicator (LLM-proposed schema growth)
  data/
    ibkr.clj                ib-re-actor wrapper; callback-shaped (NOT core.async)
    ibkr/pacer.clj          Token-bucket + 10-min historical-data window
    ibkr/request_id.clj     Request-id manager + per-type callback registries
    ibkr/subscriptions.clj  Streaming-sub manager (positions + investigation only)
    market_data.clj         Snapshot / streaming dispatch on top of ibkr
    edgar.clj               EDGAR filings + caching + 13D/G activist flag
    fundamentals.clj        IB fundamentals XML → normalised rows
    earnings.clj            earnings_events + earnings_calendar
    news.clj                IB news + HTTP fallback
    short_interest.clj      Stub-by-default; pluggable
    sectors.clj             GICS map (CSV default)
    events.clj              FDA / investor day / catalyst calendar (stub default)
    universes.clj           Wikipedia + CSV constituents (data-driven configs)
    options.clj             Option-chain helpers
  db/
    duckdb.clj              Connection pool, numbered migrations, schema bootstrap
    refresh.clj             refresh-bars-daily!/-intraday!/-news!/-fundamentals!/
                            -filings!/-universes!/-portfolio!.  Self-healing via
                            MAX(date|ts) per symbol so a missed run is recovered
                            by the next one.
  indicators/
    ta4j.clj                Java interop wrapper over ta4j 0.16
    engine.clj              Reads resources/indicators.edn; ALTER TABLE on demand;
                            populates latest_indicators + indicator_history
    runner.clj              Topo-sorts indicator deps; runs per refresh
    composites.clj          ttm_squeeze_flag, macd_cross_flag, mass_reversal_flag, …
    iv.clj                  iv_rank_*/iv_percentile_* via DuckDB windows
    beta.clj                Beta against SPY (or configured index)
    earnings_views.clj      Earnings calendar / nearest-event views
    fundamentals_views.clj  Ratio columns derived from SEC EDGAR
    sector_metrics.clj      Per-GICS sector aggregates
    price_action_views.clj  ATR%, 52w-high distance, drawdown
    option_chain_agg.clj    Aggregates by underlying (mean IV, OI sum, …)
    event_flags.clj         Boolean event-proximity flags
  screener/
    registry.clj            list/get/save/delete/run; the ONLY entry point.
                            run-screen routes: inline SQL → cached SQL (when
                            description-hash matches) → fresh LLM generation.
    nl.clj                  schema-summary + description→SQL via tui.llm/complete.
                            LLM may reply MISSING <col> to signal a yet-uncomputed
                            indicator; caller dispatches to :propose-indicator.
    sentiment.clj           VADER-style scorer
  portfolio/
    core.clj                positions + account_summary refresh, P&L mult
    risk.clj                Order guard (max-loss, margin, position-cap)
    views.clj               portfolio_summary + portfolio_greeks
  tui/
    main.clj                Lanterna entry; @state atom; key dispatch loop
    claude_proc.clj         Spawn `claude --output-format stream-json`; channel of events
    llm.clj                 Claude Code only: defonce active-model atom; /model
                            switches between Opus/Sonnet/Haiku model ids.
                            complete / complete-json are sync one-shots for
                            slash commands and screener NL.
    runtime_context.clj     Three-layer system-prompt assembly
    slash.clj               Local slash commands (/run /investigate /promote /model …)
    conversation.clj        Session-id store + JSONL replay
  mcp/
    server.clj              -main for :mcp-server alias (stdio JSON-RPC)
    protocol.clj            initialize / tools-list / tools-call
    tools.clj               Tool registry; 18 tools (portfolio_summary, list_screens,
                            run_screen, run_sql, get_indicators, place_order,
                            cancel_order, plus 11 fetch_* research tools)
resources/
  config.edn                aero profiles :dev (paper 7497) / :prod (live 7496)
  indicators.edn            SOURCE OF TRUTH for indicator columns + percentile windows
  event-flags.edn           Catalyst-proximity rules
  sentiment-lexicon.edn     VADER-style scorer default
  mcp/tools/*.json          One JSON Schema per MCP tool
  runtime/CLAUDE.md         Runtime system prompt (base layer for in-session claude)
  screens/*.screen          Starter screens — front-matter + SQL body OR description
  universes/*.universe      Static SP500/NDX/Mag7/etc. snapshots
  universes/sources.edn     Per-index URL + selector + ticker column
  universes/symbol-rules.edn Wikipedia → IB normalisation rules
  fixtures/                 Test fixtures (HTML, EDN)
db/migrations/*.sql         Numbered migrations applied above
                            _schema_migrations.last_applied. Idempotent on re-run.
bin/refresh                 Cron-safe wrapper: flock + log rotation, dispatches
                            to `clojure -M:cli refresh-<task>`
test/options_trader/        Mirrors src/ layout; kaocha runner
```

## Commands

```bash
clojure -M:run init               # First-run wizard: DB + .claude/settings.local.json
clojure -M:run --check-config     # Validate resources/config.edn (no DB/TWS); exit 0/1
clojure -M:run                    # Launch the TUI
clojure -M:run --headless ...     # Disable Lanterna; run subcommand stdout-only
clojure -M:cli refresh-daily      # All refresh-* subcommands map to db.refresh fns:
clojure -M:cli refresh-intraday   #   -daily / -intraday / -news / -fundamentals /
clojure -M:cli refresh-news       #   -filings / -universes / -portfolio / ping
clojure -M:cli portfolio          # Read-only views: portfolio, screen, ping, …
clojure -M:cli screen <name>      # Run a saved screen
clojure -M:mcp-server             # Stdio MCP server (spawned by claude per session)
clojure -M:test                   # Kaocha test suite (mirrors src/ layout)
bb tasks                          # repl, test, lint, format, run-tui, backup-db, …

bin/refresh daily                 # Cron-safe wrapper; flocked + logged. One per task.
```

`ANTHROPIC_API_KEY` is **not** required if you're on the Claude Pro/Max plan —
the `claude` CLI subprocess uses its own cached login under `~/.claude/`.
Secrets that *are* needed (e.g. third-party data feeds) live in `.envrc`
(gitignored), NEVER in `config.edn`.

## Architectural invariants — do not violate

* **IB wrapper is callback-shaped.** `data.ibkr` keeps the explicit
  `cb`-takes-an-event style. core.async is reserved for boundaries
  (TUI loop, pacer queue, P&L mult, claude stdout). Do not rewrite
  the IB layer with channels.
* **Streaming subscriptions are scarce.** Subscribe ONLY to held
  positions and the single active investigation symbol. Everything
  else uses `req-market-data-snapshot` (auto-cancels). Hard cap is
  enforced by `data.ibkr.subscriptions`.
* **DuckDB has one writer.** The TUI process owns the writer
  connection. The MCP server opens read-only and proxies action
  tools through `actions/handle-action`. Do not open a second writer.
* **Refresh is self-healing.** Every `db.refresh` fn queries
  `MAX(bar_date|bar_ts|published_at|filed_at)` per symbol and pulls
  only the gap. A failed or skipped run is corrected by the next one
  observing the same stale MAX. Do not introduce stateful cursors.
* **Indicators grow declaratively.** Adding a column = adding an
  entry to `resources/indicators.edn`. The engine handles
  `ALTER TABLE ADD COLUMN IF NOT EXISTS` and backfill. Renames keep
  the old column for the deprecation window in `indicator_renames`.
* **Screens are SQL or NL — never code.** A `.screen` file has YAML
  front-matter and either a SQL body or a description-only body. The
  watcher upserts within ~250 ms. Description-driven screens cache
  generated SQL keyed on the description-hash; the cache invalidates
  when the description text changes.
* **Missing indicators are an action, not a code change.** When the
  LLM signals `MISSING <col>` during description→SQL generation,
  `run-screen` returns `{:missing col}`. The caller routes to
  `:propose-indicator` (LLM proposes a spec from `ta4j/known-kinds`)
  then `:add-indicator` with `:confirm? true` (writes to
  indicators.edn; the next refresh adds the column + backfills).
* **No DSL.** The screener has no custom grammar. `run_sql` and
  `run_screen` MCP tools execute SQL directly via `next.jdbc`. Writes
  are not enforced at the SQL layer — by design no agent surface
  exposes a write tool. Writes are owned by `db.refresh` (CLI/cron)
  and action handlers (`place_order`, `cancel_order`, `add-indicator`).
* **Action guard is inside the handler.** `:allow-orders?`, max-loss,
  market hours, margin, and position-pct-net-liq live in
  `actions/handle-action :place-order`. The MCP server, CLI, and
  TUI all call the same fn so the guard can't be skipped at a surface.
* **Confirm-gated actions take `:confirm? true`.** First call returns
  a preview map; second call with `:confirm? true` applies. Used by
  `:add-indicator` and `:place-order` (via `place_order` MCP tool).
* **Pinned versions.** `ib-re-actor-976-plus` and TWS/Gateway are
  user-compiled and version-locked. Bumps require an explicit user
  decision — never a default upgrade.
* **Two CLAUDE.md files, never merged.** *This* file is the **repo**
  CLAUDE.md (you reading the codebase). `resources/runtime/CLAUDE.md`
  is the **runtime** system prompt the TUI feeds to claude during
  trading sessions. They serve opposite audiences.

## Conventions

* In-Clojure values are kebab-case keywords; MCP JSON serialises to
  snake_case at the protocol boundary.
* Errors from action tools and MCP handlers are STRUCTURED maps
  (`{:error :insufficient_margin :required … :available …}`) — never
  bare strings. The agent reasons about the keys.
* Logging via timbre to `cache/logs/options-trader.log` (daily
  rotation, 14-day retention). stderr is reserved for the MCP server's
  stdio protocol — no app logs there.
* Migrations are numbered, monotonic, applied above
  `_schema_migrations.last_applied`. Idempotent on re-run.
* Public fns should carry malli schemas on inputs/outputs where they
  cross subsystem boundaries.
* Comments default to zero. Only keep a comment when it captures a
  non-obvious constraint, a workaround for a specific bug, or a *why*
  the code itself can't show.

## Extending

* **New indicator column** → either add an entry to
  `resources/indicators.edn` by hand (`:kind :params :column
  :percentile-windows`), or let a description-driven screen reference
  it; the engine adds the column with `ALTER TABLE` and backfills
  history on the next refresh.
* **New screen** → drop a `.screen` file under `resources/screens/`
  (or `<config-dir>/screens/` for user screens); the watcher upserts
  within ~250 ms. Front-matter + either SQL body OR a natural-language
  description — pick one.
* **New universe** → drop a `.universe` file or add a config entry to
  `resources/universes/sources.edn`. Daily refresh writes drift to
  `universe_drift_log` for user review.
* **New MCP tool** → add `resources/mcp/tools/<name>.json` (JSON
  Schema for input + output), register it in `mcp.tools/tool-names`,
  and add a `case` branch in `mcp.tools/call-tool`. Action tools must
  dispatch through `actions/handle-action` so the guard runs.
* **Switch Claude model** → `/model claude-haiku-4-5-20251001` (or any
  other id) in the TUI. `tui.llm/active-model` is a `defonce` atom so
  the new model sticks for the rest of the session. No provider
  abstraction — Claude Code only.

## Pointers

* `resources/runtime/CLAUDE.md` — runtime system prompt for the
  in-session agent. Do NOT edit when working on the code unless you
  intend to change agent behaviour.
* `.claude/settings.local.json` — declares the local MCP server.
  Auto-generated by `clojure -M:run init`; user-editable.
* `bin/refresh` — cron wrapper, the reference for what each refresh
  subcommand expects.
