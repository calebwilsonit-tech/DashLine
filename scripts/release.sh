#!/usr/bin/env bash
# Cut a Dashline release: tag, build the signed APK, publish it on GitHub Releases.
#
#   scripts/release.sh 0.2.0            # stable: every phone's updater offers it
#   scripts/release.sh 0.2.0-beta "Notes here"   # pre-release: Beta/Alpha channels only
#
# Needs: a clean working tree on main, DriveApp/keystore.properties (the signing key, never
# committed; it lives in ~/.dashline-signing), the gh CLI logged in, and Android Studio's JDK.
# The version code comes from the tag (see gitVersion in DriveApp/gearslip/build.gradle.kts).
set -euo pipefail
ver="${1:?usage: scripts/release.sh <version> [notes]}"
notes="${2:-}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

[[ "$ver" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-(beta|alpha))?$ ]] || { echo "version must look like 1.2.3 or 1.2.3-beta"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "commit or stash your changes first"; exit 1; }
[ -f DriveApp/keystore.properties ] || { echo "missing DriveApp/keystore.properties (copy it from ~/.dashline-signing)"; exit 1; }
git rev-parse -q --verify "refs/tags/v$ver" >/dev/null && { echo "v$ver already exists"; exit 1; }

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
git tag -a "v$ver" -m "Dashline $ver"
( cd DriveApp && ./gradlew :gearslip:assembleRelease --console=plain -q )
apk="DriveApp/build/Dashline-$ver.apk"
cp DriveApp/build/Dashline-release.apk "$apk"

git push origin main "v$ver"
pre=""; [[ "$ver" == *-* ]] && pre="--prerelease"
gh release create "v$ver" "$apk" --title "Dashline $ver" ${notes:+--notes "$notes"} ${notes:---generate-notes} $pre
echo "Released v$ver"
