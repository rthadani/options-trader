#!/bin/bash
set -e
cd "$(dirname "$0")/.."

if [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
  echo "Usage: bb refresh-data [options]"
  echo "  --universe NAME    Universe (default: sp500)"
  echo "  --profile PROFILE  Config profile (default: dev)"
  echo "  --bars TYPE       Bars: daily, intraday, both (default: daily)"
  echo "  --portfolio       Also refresh portfolio"
  echo "  --no-news         Skip news refresh"
  echo "  --no-fundamentals Skip fundamentals refresh"
  echo "  --no-filings      Skip filings refresh"
  echo "  --no-universes    Skip universes refresh"
  exit 0
fi

UNIVERSE="sp500"
PROFILE="dev"
BARS="daily"
EXCLUDE_NEWS=""
EXCLUDE_FUNDAMENTALS=""
EXCLUDE_FILINGS=""
EXCLUDE_UNIVERSES=""
RUN_PORTFOLIO=""

while [ $# -gt 0 ]; do
  case "$1" in
    --universe|-u)
      UNIVERSE="${2:-}"
      shift 2
      ;;
    --universe=*)
      UNIVERSE="${1#*=}"
      shift
      ;;
    --profile|-p)
      PROFILE="${2:-}"
      shift 2
      ;;
    --profile=*)
      PROFILE="${1#*=}"
      shift
      ;;
    --bars)
      BARS="${2:-}"
      shift 2
      ;;
    --bars=*)
      BARS="${1#*=}"
      shift
      ;;
    --portfolio)
      RUN_PORTFOLIO="1"
      shift
      ;;
    --no-news)
      EXCLUDE_NEWS="1"
      shift
      ;;
    --no-fundamentals)
      EXCLUDE_FUNDAMENTALS="1"
      shift
      ;;
    --no-filings)
      EXCLUDE_FILINGS="1"
      shift
      ;;
    --no-universes)
      EXCLUDE_UNIVERSES="1"
      shift
      ;;
    *)
      shift
      ;;
  esac
done

export UNIVERSE PROFILE BARS EXCLUDE_NEWS EXCLUDE_FUNDAMENTALS EXCLUDE_FILINGS EXCLUDE_UNIVERSES RUN_PORTFOLIO

bash tasks/refresh-data-clj.sh
