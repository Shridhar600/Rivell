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
# Pages serves the nearest 404.html for an unknown path, so a deep link or a refresh inside the
# demo loads the demo page and its router shows the right screen.
cp "$REPO/site/demo/index.html" "$REPO/site/demo/404.html"
echo "site ready in $REPO/site (demo at site/demo)"
