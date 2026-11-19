#!/usr/bin/env bash
# Builds the release APK and publishes it as the newest GitHub Release, so the one download link
#   https://github.com/<owner>/Yuno/releases/latest/download/Yuno.apk
# always serves the latest build. The release is tagged v<versionName>; publishing the same
# version again replaces that release's APK and notes. Build locally, not in CI: the release is signed
# with this Mac's debug key, and a different key won't install over the app already on the phone.
#
# Release notes (issue #30): CHANGELOG.md's section for this version becomes the release's notes,
# which the app's What's new sheet shows before updating. Changes go under "## Unreleased" as they're
# made; publishing renames that section to the version (commit CHANGELOG.md afterwards). No notes =
# no release.
#
#   scripts/publish-apk.sh            # build, then publish
#   scripts/publish-apk.sh --no-build # publish the APK already in app/build
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
GH="$(command -v gh || echo "$HOME/.local/bin/gh")"

version="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
tag="v$version"
# origin is Yuno. A remote named upstream makes gh publish to that repo instead.
origin_url="$(git remote get-url origin)"
origin_repo="${origin_url%.git}"
origin_repo="${origin_repo#https://github.com/}"
origin_repo="${origin_repo#git@github.com:}"
repo_flag=(--repo "$origin_repo")

# The lines under "## <heading>" up to the next "## ".
section() { awk -v h="$1" '/^## /{ if (on) exit; on = (substr($0, 4) == h); next } on' CHANGELOG.md; }
from="$version"
notes="$(section "$version")"
if ! grep -q '^[-*] ' <<<"$notes"; then
  from="Unreleased"
  notes="$(section Unreleased)"
  grep -q '^[-*] ' <<<"$notes" || { echo "CHANGELOG.md has no notes for $version: add them under '## Unreleased'" >&2; exit 1; }
fi

[[ "${1:-}" == "--no-build" ]] || ./gradlew assembleRelease --console=plain

apk="$(mktemp -d)/Yuno.apk"
cp app/build/outputs/apk/release/app-release.apk "$apk"
body="$(dirname "$apk")/notes.md"
printf '%s\n\n---\n\nInstall Yuno.apk on Android (allow installs from your browser or file manager). Installed copies update from the Update button on Home.\n' \
  "$(sed -e '/./,$!d' <<<"$notes")" >"$body"

if "$GH" release view "$tag" "${repo_flag[@]}" >/dev/null 2>&1; then
  "$GH" release upload "$tag" "$apk" --clobber "${repo_flag[@]}"
  "$GH" release edit "$tag" --latest --notes-file "$body" "${repo_flag[@]}"
else
  "$GH" release create "$tag" "$apk" --title "Yuno $version" --latest --notes-file "$body" "${repo_flag[@]}"
fi

# The published notes become this version's section; a fresh, empty Unreleased goes above it.
if [[ "$from" == "Unreleased" ]]; then
  V="$version" perl -0pi -e 's/^## Unreleased(\r?\n)/## Unreleased$1$1## $ENV{V}$1/m' CHANGELOG.md
  echo "CHANGELOG.md: Unreleased is now $version (commit it)"
fi

repo="$("$GH" repo view "$origin_repo" --json nameWithOwner -q .nameWithOwner)"
echo "Published $tag: https://github.com/$repo/releases/latest/download/Yuno.apk"
