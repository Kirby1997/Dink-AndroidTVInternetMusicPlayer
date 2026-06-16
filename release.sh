#!/usr/bin/env bash
# Build signed release artifacts for Dink.
#   ./release.sh        -> AAB (for Play) + APK (for sideload)
#   ./release.sh aab    -> AAB only
#   ./release.sh apk    -> APK only
#
# Requires keystore.properties (see RELEASE.md). Without it the build still runs but is
# DEBUG-SIGNED and cannot be uploaded to Play — the script warns loudly.
set -euo pipefail
cd "$(dirname "$0")"

WHAT="${1:-both}"

if [[ ! -f keystore.properties ]]; then
  echo "!! keystore.properties missing — output will be DEBUG-SIGNED and NOT uploadable."
  echo "!! See RELEASE.md to create the upload key."
fi

echo ">> clean"
./gradlew clean

case "$WHAT" in
  aab)  ./gradlew bundleRelease ;;
  apk)  ./gradlew assembleRelease ;;
  both) ./gradlew bundleRelease assembleRelease ;;
  *) echo "usage: $0 [aab|apk|both]"; exit 1 ;;
esac

echo
echo ">> artifacts:"
find app/build/outputs -name "*.aab" -o -name "*-release.apk" 2>/dev/null | sed 's/^/   /'
