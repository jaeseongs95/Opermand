#!/usr/bin/env bash
# Spike item 12: signature / attestation / SBOM / licence notices of the upstream release.
# Needs GH_TOKEN (the workflow's own token) for `gh`. Output goes to $OUT.
set -u
OUT="${OUT:?}"; mkdir -p "$OUT"
BASE="${RELEASE_BASE:?}"; REPO="${UPSTREAM_REPO:?}"; TAG="${UPSTREAM_TAG:?}"
LOG="$OUT/provenance.log"; : > "$LOG"
say() { echo "$@" | tee -a "$LOG"; }

verify_asset() { # name sha
  local name="$1" sha="$2"
  curl -fsSL -o "$name" "$BASE/$name" && curl -fsSL -o "$name.sha256" "$BASE/$name.sha256"
  local actual sidecar
  actual=$(sha256sum "$name" | cut -d' ' -f1); sidecar=$(cut -d' ' -f1 < "$name.sha256")
  say "SHA256 $name pinned=$sha sidecar=$sidecar actual=$actual size=$(stat -c %s "$name")"
  [ "$actual" = "$sha" ] && [ "$sidecar" = "$sha" ] || { say "RESULT item=12 check=sha256($name) verdict=fail"; return 1; }
  say "RESULT item=12 check=sha256($name) verdict=pass"
}

cd "$(mktemp -d)"
verify_asset "$WIN_ASSET" "$WIN_SHA" || exit 1
verify_asset "$LINUX_ASSET" "$LINUX_SHA" || exit 1

for a in "$WIN_ASSET" "$LINUX_ASSET"; do
  say "--- gh attestation verify $a"
  if gh attestation verify "$a" -R "$REPO" >"$OUT/attestation-$a.txt" 2>&1; then
    say "RESULT item=12 check=gh-attestation-verify($a) verdict=pass"
  else
    say "RESULT item=12 check=gh-attestation-verify($a) verdict=fail (exit $?)"
  fi
  head -c 1500 "$OUT/attestation-$a.txt" | tee -a "$LOG"
  say "--- archive listing $a"
  tar -tzvf "$a" | tee -a "$LOG"
done

say "--- release assets (names only)"
gh release view "$TAG" -R "$REPO" --json assets --jq '.assets[].name' | tee -a "$LOG" > "$OUT/release-assets.txt"
if grep -iqE 'sbom|cyclonedx|spdx|\.sig$|\.asc$|\.pem$|\.intoto|provenance|licen[sc]e|third' "$OUT/release-assets.txt"; then
  say "RESULT item=12 check=release has sbom/signature/license assets verdict=info present: $(grep -iE 'sbom|cyclonedx|spdx|\.sig$|\.asc$|\.pem$|\.intoto|provenance|licen[sc]e|third' "$OUT/release-assets.txt" | tr '\n' ' ')"
else
  say "RESULT item=12 check=release has sbom/signature/license assets verdict=info none of the $(wc -l < "$OUT/release-assets.txt") release assets is an SBOM, detached signature or licence file"
fi

say "--- licence / notice files in the upstream repo at the tag"
gh api "repos/$REPO/git/trees/$TAG?recursive=1" --jq '.tree[].path' 2>>"$LOG" | grep -iE '(^|/)(LICENSE|NOTICE|THIRD[-_ ]?PARTY|deny\.toml|about\.toml|sbom)[^/]*$|crates/ironrdp-agent/(Cargo.toml|LICENSE)' | tee -a "$LOG" > "$OUT/upstream-licence-files.txt"
gh api "repos/$REPO/contents/crates/ironrdp-agent/Cargo.toml?ref=$TAG" -H 'Accept: application/vnd.github.raw' 2>>"$LOG" | grep -E '^(name|version|license|edition|rust-version)' | tee -a "$LOG"
say "--- upstream workflows that build/sign/attest the release"
gh api "repos/$REPO/git/trees/$TAG?recursive=1" --jq '.tree[].path' | grep -E '^\.github/workflows/.*(release|agent)' | tee -a "$LOG"
