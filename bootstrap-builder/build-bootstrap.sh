#!/usr/bin/env bash
#
# Build a Termux bootstrap (plus an apt repository) for a CUSTOM Android package name.
#
# Why: Termux binaries have their prefix compiled in (/data/data/com.termux/files/usr).
# They only work from an app whose package name is com.termux. For any other app the
# whole bootstrap has to be rebuilt from source with TERMUX_APP__PACKAGE_NAME changed
# (this is the fork flow supported by termux-packages, see scripts/properties.sh).
#
# Usage:
#   PACKAGE_NAME=com.example.app REPO_URL=https://me.github.io/repo/apt \
#   ARCH=aarch64 EXTRA_PACKAGES="python git" ./bootstrap-builder/build-bootstrap.sh
#   ./bootstrap-builder/build-bootstrap.sh release <dir containing apt-<arch>/ dirs>
#
# Needs: git docker jq zip unzip curl xz-utils dpkg-dev binutils apt-utils
# Takes hours (everything is compiled from source inside Docker).
set -euo pipefail

die() { echo "[!] $*" >&2; exit 1; }
log() { echo "[*] $*"; }

# ── release subcommand: merge per-arch repos and write the apt Release file ──
if [[ "${1:-}" == "release" ]]; then
  SRC="${2:?usage: build-bootstrap.sh release <dir with apt-<arch> dirs>}"
  OUT="${3:-$SRC/apt}"
  mkdir -p "$OUT"
  for d in "$SRC"/apt-*/; do cp -a "$d". "$OUT"/; done
  archs=$(ls "$OUT/dists/stable/main" | sed 's/^binary-//' | tr '\n' ' ')
  ( cd "$OUT" && apt-ftparchive \
      -o APT::FTPArchive::Release::Origin=aeonruntime \
      -o APT::FTPArchive::Release::Label=aeonruntime \
      -o APT::FTPArchive::Release::Suite=stable \
      -o APT::FTPArchive::Release::Codename=stable \
      -o APT::FTPArchive::Release::Architectures="$archs" \
      -o APT::FTPArchive::Release::Components=main \
      release dists/stable > dists/stable/Release.tmp && mv dists/stable/Release.tmp dists/stable/Release )
  log "apt repository ready in $OUT (architectures: $archs)"
  exit 0
fi

PACKAGE_NAME="${PACKAGE_NAME:?set PACKAGE_NAME, e.g. com.example.app}"
REPO_URL="${REPO_URL:?set REPO_URL: public https base URL where the apt repo will be hosted (no trailing slash)}"
ARCH="${ARCH:-aarch64}"                       # aarch64 | arm | i686 | x86_64
EXTRA_PACKAGES="${EXTRA_PACKAGES:-}"          # space separated, built into the repo (e.g. "python git")
TERMUX_PACKAGES_REF="${TERMUX_PACKAGES_REF:-master}"   # branch, tag or commit SHA
WORK_DIR="${WORK_DIR:-$PWD/work}"
OUT_DIR="${OUT_DIR:-$PWD/dist}"

[[ "$PACKAGE_NAME" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]] || die "invalid package name: $PACKAGE_NAME"
(( ${#PACKAGE_NAME} <= 40 )) || log "WARNING: long package name; shebang paths must stay under 127 chars"
REPO_URL="${REPO_URL%/}"

TP="$WORK_DIR/termux-packages-$ARCH"
IMAGE="aeonruntime-builder:${PACKAGE_NAME}"
REPO_DIR="$WORK_DIR/apt-$ARCH"
mkdir -p "$WORK_DIR" "$OUT_DIR"

# 1. termux-packages at the requested ref
if [[ ! -d "$TP/.git" ]]; then
  log "Fetching termux-packages @ $TERMUX_PACKAGES_REF"
  git init -q "$TP"
  git -C "$TP" remote add origin https://github.com/termux/termux-packages.git
  git -C "$TP" fetch -q --depth 1 origin "$TERMUX_PACKAGES_REF"
  git -C "$TP" checkout -q FETCH_HEAD
fi
log "termux-packages commit: $(git -C "$TP" rev-parse HEAD)"

# 2. Point the build at our package name and our apt repository
sed -i "s|^TERMUX_APP__PACKAGE_NAME=\"com.termux\"|TERMUX_APP__PACKAGE_NAME=\"${PACKAGE_NAME}\"|" "$TP/scripts/properties.sh"
grep -q "^TERMUX_APP__PACKAGE_NAME=\"${PACKAGE_NAME}\"" "$TP/scripts/properties.sh" \
  || die "could not patch TERMUX_APP__PACKAGE_NAME in scripts/properties.sh (upstream layout changed?)"
jq --arg u "$REPO_URL" '.packages.url = $u' "$TP/repo.json" > "$TP/repo.json.new" && mv "$TP/repo.json.new" "$TP/repo.json"

# 3. Builder image must be rebuilt: the official one has the com.termux prefix baked in
log "Building docker builder image $IMAGE"
docker build -q -f "$TP/scripts/Dockerfile" -t "$IMAGE" "$TP/scripts"

# 4. Package list = everything generate-bootstraps.sh installs + apt + extras
BOOT_PKGS=$(grep -oE 'pull_package [a-z0-9_.+-]+' "$TP/scripts/generate-bootstraps.sh" | awk '{print $2}' | sort -u | tr '\n' ' ')
PKGS="apt dpkg $BOOT_PKGS $EXTRA_PACKAGES"
log "Compiling for $ARCH (dependencies are built from source too): $PKGS"
( cd "$TP" && rm -rf output && \
  TERMUX_BUILDER_IMAGE_NAME="$IMAGE" CONTAINER_NAME="aeonruntime-builder-${ARCH}" \
  ./scripts/run-docker.sh ./build-package.sh -a "$ARCH" -o output $PKGS )

# 5. Turn output/*.deb into an apt repository layout
log "Creating apt repository"
rm -rf "$REPO_DIR"
mkdir -p "$REPO_DIR/pool/main" "$REPO_DIR/dists/stable/main/binary-$ARCH"
cp "$TP"/output/*.deb "$REPO_DIR/pool/main/"
( cd "$REPO_DIR" && dpkg-scanpackages --multiversion pool /dev/null > "dists/stable/main/binary-$ARCH/Packages" \
    && gzip -kf "dists/stable/main/binary-$ARCH/Packages" )

# 6. Assemble the bootstrap zip from *our* repository
log "Generating bootstrap-$ARCH.zip"
( cd "$TP" && ./scripts/generate-bootstraps.sh --architectures "$ARCH" --repository "file://$REPO_DIR" )
ZIP="$TP/bootstrap-$ARCH.zip"
[[ -f "$ZIP" ]] || die "generate-bootstraps.sh did not produce $ZIP"

# 7. apt must use our repo (unsigned -> trusted=yes; the repo is served over HTTPS)
TMP=$(mktemp -d); mkdir -p "$TMP/etc/apt"
printf '# aeonruntime repository for %s\ndeb [trusted=yes] %s stable main\n' "$PACKAGE_NAME" "$REPO_URL" > "$TMP/etc/apt/sources.list"
( cd "$TMP" && zip -q "$ZIP" etc/apt/sources.list ); rm -rf "$TMP"

# 8. Sanity checks: the prefix must match PACKAGE_NAME and no com.termux paths may remain
unzip -p "$ZIP" SYMLINKS.txt | grep -q "/data/data/${PACKAGE_NAME}/files/usr" \
  || die "SYMLINKS.txt does not reference /data/data/${PACKAGE_NAME}/files/usr"
for f in lib/libapt-pkg.so bin/dpkg bin/pkg; do
  if unzip -p "$ZIP" "$f" 2>/dev/null | strings -a | grep -q "/data/data/com\.termux"; then
    die "$f still contains /data/data/com.termux — package name patch did not apply"
  fi
done
cp "$ZIP" "$OUT_DIR/bootstrap-$ARCH.zip"
rm -rf "$OUT_DIR/apt-$ARCH" && cp -a "$REPO_DIR" "$OUT_DIR/apt-$ARCH"
log "Done: $OUT_DIR/bootstrap-$ARCH.zip and $OUT_DIR/apt-$ARCH/"
