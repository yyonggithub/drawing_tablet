#!/usr/bin/env bash
#
# One-click Docker entry point for the Drawing Tablet project.
#
# The images cover DEVELOPMENT / BUILD / PACKAGE only — the server itself
# must run on the host (Wayland screen-capture portals, PipeWire and uinput
# are not available inside a container).
#
# Usage:
#   ./docker/dt.sh image  [server|android|all]  build the image(s)
#   ./docker/dt.sh dev    [server|android]      interactive shell, source at /src
#   ./docker/dt.sh build  [server|android]      compile (artifacts in worktree)
#   ./docker/dt.sh package                      server: bin + tarball + Arch pkg -> dist/
#   ./docker/dt.sh apk                          Android APK -> dist/
#
# Environment (optional):
#   KEYSTORE_PASSWORD / KEY_PASSWORD  release APK signing (needs
#                                     android/drawing_tablet.keystore too)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

IMG_SERVER="drawing-tablet-server:local"
IMG_ANDROID="drawing-tablet-android:local"
VOL_CARGO="dt-cargo-registry"
VOL_GRADLE="dt-gradle-home"
DIST="$ROOT/dist"

die()  { echo "error: $*" >&2; exit 1; }
info() { echo "==> $*"; }

usage() {
    sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# --- image handling -----------------------------------------------------------

image_name() {
    case "${1:-server}" in
        server)  echo "$IMG_SERVER" ;;
        android) echo "$IMG_ANDROID" ;;
        *) die "unknown target '$1' (want: server | android)" ;;
    esac
}

cmd_image() {
    case "${1:-server}" in
        all)   cmd_image server; cmd_image android; return ;;
        server|android) ;;
        *) die "unknown target '$1' (want: server | android | all)" ;;
    esac
    local t="$1" img
    img="$(image_name "$t")"
    info "building image $img (first run downloads toolchains)"
    docker build -f "docker/Dockerfile.$t" --build-arg "UID=$(id -u)" -t "$img" "$ROOT"
}

ensure_image() {
    local img
    img="$(image_name "$1")"
    docker image inspect "$img" >/dev/null 2>&1 || cmd_image "$1"
}

# --- host/bridge details ------------------------------------------------------
#
# AGP prefers `sdk.dir` from the host's android/local.properties over
# ANDROID_HOME. When that file points somewhere that only exists on the host,
# create the same path inside the container as a symlink to the container SDK.

sdk_link_env=()
sdk_pre=""
if [[ -f "$ROOT/android/local.properties" ]]; then
    host_sdk="$(sed -n 's/^sdk\.dir=//p' "$ROOT/android/local.properties" | tail -1)"
    if [[ -n "$host_sdk" ]]; then
        sdk_link_env=(-e "DT_HOST_SDK_LINK=$host_sdk")
        sdk_pre='if [ -n "${DT_HOST_SDK_LINK:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then { sudo mkdir -p "$(dirname "$DT_HOST_SDK_LINK")" && sudo ln -sfn "$ANDROID_HOME" "$DT_HOST_SDK_LINK"; } || echo "warning: could not link $DT_HOST_SDK_LINK to $ANDROID_HOME"; fi; '
    fi
fi

# Run a server container with the worktree mounted at /src ($1 = command,
# $2 = optional workdir).
run_server() {
    docker run --rm \
        -v "$ROOT:/src" -w "${2:-/src}" \
        -v "$VOL_CARGO:/home/builder/.cargo" \
        "$IMG_SERVER" bash -c "$1"
}

# Run an Android container with the worktree mounted at /src ($1 = command).
run_android() {
    # shellcheck disable=SC2086
    docker run --rm \
        -v "$ROOT:/src" -w /src \
        -v "$VOL_GRADLE:/home/builder/.gradle" \
        ${sdk_link_env[@]+"${sdk_link_env[@]}"} \
        -e KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}" \
        -e KEY_PASSWORD="${KEY_PASSWORD:-}" \
        "$IMG_ANDROID" bash -c "${sdk_pre}$1"
}

# --- commands -----------------------------------------------------------------

cmd_dev() {
    local t="${1:-server}"
    ensure_image "$t"
    local vols=(-v "$ROOT:/src" -w /src)
    local pre=""
    case "$t" in
        server)  vols+=(-v "$VOL_CARGO:/home/builder/.cargo") ;;
        android) vols+=(-v "$VOL_GRADLE:/home/builder/.gradle")
                 vols+=(${sdk_link_env[@]+"${sdk_link_env[@]}"})
                 pre="$sdk_pre" ;;
    esac
    local tty=(-i)
    [[ -t 0 && -t 1 ]] && tty=(-it)
    info "dev shell ($t): source mounted at /src (exit to leave)"
    exec docker run --rm "${tty[@]}" "${vols[@]}" \
        -e KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-}" \
        -e KEY_PASSWORD="${KEY_PASSWORD:-}" \
        "$(image_name "$t")" bash -c "${pre}exec bash -l"
}

cmd_build() {
    case "${1:-server}" in
        server)
            ensure_image server
            info "cargo build --release"
            run_server 'cargo build --release'
            info "artifact: target/release/dt-server"
            ;;
        android)
            ensure_image android
            info "gradle assembleDebug"
            run_android 'cd android && ./gradlew assembleDebug'
            info "artifact: android/app/build/outputs/apk/debug/app-debug.apk"
            ;;
        *) die "unknown target '$1' (want: server | android)" ;;
    esac
}

cmd_package() {
    ensure_image server
    local pkgver work stage tarball
    pkgver="$(sed -n 's/^pkgver=//p' PKGBUILD | head -1)"
    [[ -n "$pkgver" ]] || die "could not read pkgver from PKGBUILD"
    work="$DIST/.pkgbuild"
    mkdir -p "$DIST"
    rm -rf "$work"
    mkdir -p "$work"

    info "1/4 cargo build --release"
    run_server 'cargo build --release'

    info "2/4 dist/dt-server + dist/drawing-tablet-$pkgver-linux-x86_64.tar.gz"
    stage="$work/stage/drawing-tablet-$pkgver-linux-x86_64"
    mkdir -p "$stage"
    cp -f target/release/dt-server "$stage/drawing-tablet"
    cp -f drawing-tablet.desktop "$stage/"
    cp -f crates/dt-server/assets/icon.png "$stage/"
    cp -f LICENSE README.md README.zh-CN.md "$stage/"
    tarball="$DIST/drawing-tablet-$pkgver-linux-x86_64.tar.gz"
    tar -czf "$tarball" -C "$work/stage" "drawing-tablet-$pkgver-linux-x86_64"
    rm -rf "$work/stage"

    info "3/4 PKGBUILD source switched to the local working tree"
    tar -czf "$work/drawing-tablet-$pkgver.tar.gz" \
        --transform "s,^\.,drawing_tablet-$pkgver," \
        -C "$ROOT" \
        --exclude='./.git' --exclude='./target' --exclude='./dist' \
        --exclude='./android/app/build' --exclude='./android/build' \
        --exclude='./android/.gradle' --exclude='./.commandcode' \
        ./.
    sed 's|^source=.*|source=("$pkgname-$pkgver.tar.gz")|' PKGBUILD > "$work/PKGBUILD"

    info "4/4 makepkg (release build + tests + Arch package)"
    run_server 'makepkg -f --noconfirm' "/src/dist/.pkgbuild"

    local pkg
    pkg="$(find "$work" -maxdepth 1 -name "drawing-tablet-$pkgver-*.pkg.tar.*" | head -1)"
    [[ -n "$pkg" ]] || die "makepkg produced no package"
    cp -f "$pkg" "$DIST/"
    rm -rf "$work"

    info "done — dist/:"
    ls -lh "$DIST" | tail -n +2 | sed 's/^/    /'
}

cmd_apk() {
    ensure_image android
    local variant=debug task=assembleDebug
    if [[ -n "${KEYSTORE_PASSWORD:-}" && -n "${KEY_PASSWORD:-}" \
          && -f "$ROOT/android/drawing_tablet.keystore" ]]; then
        variant=release task=assembleRelease
        info "keystore + env present -> release APK"
    else
        info "no keystore/env (KEYSTORE_PASSWORD, KEY_PASSWORD) -> debug APK"
    fi
    run_android "cd android && ./gradlew $task"

    local version
    version="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' android/app/build.gradle.kts | head -1)"
    [[ -n "$version" ]] || version="0.0.0"
    mkdir -p "$DIST"
    cp -f "android/app/build/outputs/apk/$variant/"*.apk \
        "$DIST/drawing-tablet-$version-$variant.apk"
    info "done — dist/drawing-tablet-$version-$variant.apk"
}

# --- dispatch -----------------------------------------------------------------

cmd="${1:-help}"
shift || true
case "$cmd" in
    image)   cmd_image "${1:-server}" ;;
    dev)     cmd_dev "${1:-server}" ;;
    build)   cmd_build "${1:-server}" ;;
    package) cmd_package ;;
    apk)     cmd_apk ;;
    help|-h|--help) usage ;;
    *) usage; die "unknown command '$cmd'" ;;
esac
