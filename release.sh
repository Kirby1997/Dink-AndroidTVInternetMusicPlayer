#!/usr/bin/env bash
# Build signed release artifacts for Dink.
#   ./release.sh        -> AAB (for Play) + APK (for sideload)
#   ./release.sh aab    -> AAB only
#   ./release.sh apk    -> APK only
#
# Requires keystore.properties (see RELEASE.md). Without it the script refuses to run, since
# the output would be DEBUG-SIGNED and not uploadable. DINK_ALLOW_DEBUG_SIGN=1 overrides
# (local smoke builds only). Unit tests and release lint must pass before anything is built.
set -euo pipefail
cd "$(dirname "$0")"

WHAT="${1:-both}"

case "$WHAT" in
  aab)  TASKS=(bundleRelease) ;;
  apk)  TASKS=(assembleRelease) ;;
  both) TASKS=(bundleRelease assembleRelease) ;;
  *) echo "usage: $0 [aab|apk|both]"; exit 1 ;;
esac

if [[ ! -f keystore.properties ]]; then
  if [[ "${DINK_ALLOW_DEBUG_SIGN:-}" == "1" ]]; then
    echo "!! keystore.properties missing — DINK_ALLOW_DEBUG_SIGN=1, output is DEBUG-SIGNED and NOT uploadable."
  else
    echo "!! keystore.properties missing — refusing to build a debug-signed release." >&2
    echo "!! See RELEASE.md to create the upload key (or DINK_ALLOW_DEBUG_SIGN=1 for a local smoke build)." >&2
    exit 1
  fi
fi

echo ">> clean"
bash ./gradlew clean

echo ">> unit tests + release lint"
bash ./gradlew :app:testDebugUnitTest :app:lintRelease

echo ">> build ${TASKS[*]}"
bash ./gradlew "${TASKS[@]}"

echo
echo ">> artifacts:"
find app/build/outputs -name "*.aab" -o -name "*-release.apk" 2>/dev/null | sed 's/^/   /'
