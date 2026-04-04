#!/usr/bin/env bash
set -euo pipefail

APP_NAME="${APP_NAME:-sipserver}"
VERSION="${VERSION:-0.1.0}"
MAINTAINER="${MAINTAINER:-sipserver maintainer}"
DESCRIPTION="${DESCRIPTION:-SIP B2BUA/registrar service built with sipgo}"
TARGET_OS="${TARGET_OS:-linux}"
TARGET_ARCH="${TARGET_ARCH:-arm64}"
TARGET_GOARM="${TARGET_GOARM:-7}"
OUT_DIR="${OUT_DIR:-./dist}"
WORK_DIR="${WORK_DIR:-$(mktemp -d /tmp/${APP_NAME}-pkg-XXXXXX)}"
BIN_DIR="${BIN_DIR:-./bin}"
BUILD_BIN="${BUILD_BIN:-$BIN_DIR/${APP_NAME}-${TARGET_OS}-${TARGET_ARCH}}"

map_deb_arch() {
  case "$1" in
    arm64) echo "arm64" ;;
    arm)
      case "${2:-7}" in
        7) echo "armhf" ;;
        6) echo "armel" ;;
        *) echo "armhf" ;;
      esac
      ;;
    amd64) echo "amd64" ;;
    *) echo "$1" ;;
  esac
}

DEB_ARCH="${DEB_ARCH:-$(map_deb_arch "$TARGET_ARCH" "$TARGET_GOARM")}"
PKG_ROOT="$WORK_DIR/${APP_NAME}_${VERSION}_${DEB_ARCH}"
CONTROL_DIR="$PKG_ROOT/DEBIAN"
DATA_ROOT="$PKG_ROOT"
PACKAGE_FILE="$OUT_DIR/${APP_NAME}_${VERSION}_${DEB_ARCH}.deb"

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

build_binary() {
  mkdir -p "$BIN_DIR"
  if [[ "$TARGET_ARCH" == "arm" ]]; then
    env GOCACHE=/tmp/gocache GOOS="$TARGET_OS" GOARCH="$TARGET_ARCH" GOARM="$TARGET_GOARM" \
      go build -o "$BUILD_BIN" ./cmd/sipserver
    return
  fi

  env GOCACHE=/tmp/gocache GOOS="$TARGET_OS" GOARCH="$TARGET_ARCH" \
    go build -o "$BUILD_BIN" ./cmd/sipserver
}

write_control() {
  mkdir -p "$CONTROL_DIR"
  cat > "$CONTROL_DIR/control" <<EOF
Package: ${APP_NAME}
Version: ${VERSION}
Section: net
Priority: optional
Architecture: ${DEB_ARCH}
Maintainer: ${MAINTAINER}
Depends: systemd
Description: ${DESCRIPTION}
EOF

  cat > "$CONTROL_DIR/conffiles" <<EOF
/etc/sipserver/sipserver.conf
/etc/default/sipserver
EOF

  install -m 0755 ./packaging/debian/postinst "$CONTROL_DIR/postinst"
  install -m 0755 ./packaging/debian/prerm "$CONTROL_DIR/prerm"
  install -m 0755 ./packaging/debian/postrm "$CONTROL_DIR/postrm"
}

populate_data() {
  install -d -m 0755 "$DATA_ROOT/opt/sipserver/bin"
  install -d -m 0755 "$DATA_ROOT/etc/sipserver"
  install -d -m 0755 "$DATA_ROOT/etc/default"
  install -d -m 0755 "$DATA_ROOT/lib/systemd/system"
  install -d -m 0755 "$DATA_ROOT/var/lib/sipserver"
  install -d -m 0755 "$DATA_ROOT/var/log/sipserver"

  install -m 0755 "$BUILD_BIN" "$DATA_ROOT/opt/sipserver/bin/${APP_NAME}"
  install -m 0644 ./config.example.json "$DATA_ROOT/etc/sipserver/sipserver.conf"
  install -m 0644 ./deploy/systemd/sipserver.env.example "$DATA_ROOT/etc/default/${APP_NAME}"
  install -m 0644 ./deploy/systemd/sipserver.service "$DATA_ROOT/lib/systemd/system/${APP_NAME}.service"
}

build_ar_deb() {
  mkdir -p "$OUT_DIR"

  (
    cd "$PKG_ROOT"
    find . -mindepth 1 -maxdepth 1 | sort >/dev/null
  )

  echo "2.0" > "$WORK_DIR/debian-binary"
  COPYFILE_DISABLE=1 tar -czf "$WORK_DIR/control.tar.gz" \
    --format ustar \
    --uid 0 \
    --gid 0 \
    --uname root \
    --gname root \
    -C "$CONTROL_DIR" .
  COPYFILE_DISABLE=1 tar -czf "$WORK_DIR/data.tar.gz" \
    --format ustar \
    --uid 0 \
    --gid 0 \
    --uname root \
    --gname root \
    --exclude="./DEBIAN" \
    -C "$PKG_ROOT" .

  rm -f "$PACKAGE_FILE"
  ar -crS "$PACKAGE_FILE" "$WORK_DIR/debian-binary" "$WORK_DIR/control.tar.gz" "$WORK_DIR/data.tar.gz"
}

require_tools() {
  command -v ar >/dev/null 2>&1 || { echo "ar is required" >&2; exit 1; }
  command -v tar >/dev/null 2>&1 || { echo "tar is required" >&2; exit 1; }
  [[ -f ./config.example.json ]] || { echo "./config.example.json not found" >&2; exit 1; }
}

require_tools
build_binary
write_control
populate_data
build_ar_deb

echo "Built package: $PACKAGE_FILE"
