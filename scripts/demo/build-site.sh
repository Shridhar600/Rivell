#!/usr/bin/env bash
# Builds the static demo into site/demo/, so the website and the demo deploy as one site.
#   scripts/demo/build-site.sh
# Needs the committed capture in demo/capture (rebuild it with scripts/demo/seed.sh --capture).
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ ! -f "$REPO/demo/capture/manifest.json" ]]; then
    echo "demo/capture is missing; run scripts/demo/seed.sh --capture first" >&2
    exit 2
fi

cd "$REPO/frontend"
npm run build:demo -- --outDir "$REPO/site/demo" --emptyOutDir
echo "site ready in $REPO/site (demo at site/demo)"
