#!/bin/bash
set -e
cd "$(dirname "$0")/.."

UNIVERSE="sp500"
PROFILE="dev"
BARS="daily"
EXCLUDE_NEWS="${EXCLUDE_NEWS:-}"
EXCLUDE_FUNDAMENTALS="${EXCLUDE_FUNDAMENTALS:-}"
EXCLUDE_FILINGS="${EXCLUDE_FILINGS:-}"
EXCLUDE_UNIVERSES="${EXCLUDE_UNIVERSES:-}"

echo "=== refreshing data for $UNIVERSE ==="

if [ -n "$RUN_PORTFOLIO" ]; then
  echo ":: portfolio..."
  clojure -M:cli -u "$UNIVERSE" -p "$PROFILE" refresh-portfolio || true
fi

if echo "$BARS" | grep -q "daily"; then
  echo ":: daily bars..."
  clojure -M:cli -u "$UNIVERSE" -p "$PROFILE" refresh-daily || true
fi

if echo "$BARS" | grep -q "intraday"; then
  echo ":: intraday bars..."
  clojure -M:cli -u "$UNIVERSE" -p "$PROFILE" refresh-intraday || true
fi

if [ -z "$EXCLUDE_NEWS" ]; then
  echo ":: news..."
  clojure -M:cli -u "$UNIVERSE" -p "$PROFILE" refresh-news || true
fi

if [ -z "$EXCLUDE_FUNDAMENTALS" ]; then
  echo ":: fundamentals..."
  clojure -M:cli -p "$PROFILE" refresh-fundamentals || true
fi

if [ -z "$EXCLUDE_FILINGS" ]; then
  echo ":: filings..."
  clojure -M:cli -u "$UNIVERSE" -p "$PROFILE" refresh-filings || true
fi

if [ -z "$EXCLUDE_UNIVERSES" ]; then
  echo ":: universes..."
  clojure -M:cli -p "$PROFILE" refresh-universes || true
fi

echo "=== done ===="
