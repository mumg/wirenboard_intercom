#!/usr/bin/env bash
set -euo pipefail

APP_NAME="${APP_NAME:-sipserver}"
APP_USER="${APP_USER:-sipserver}"
APP_GROUP="${APP_GROUP:-sipserver}"
INSTALL_ROOT="${INSTALL_ROOT:-/opt/sipserver}"
CONFIG_DIR="${CONFIG_DIR:-/etc/sipserver}"
CONFIG_FILE="${CONFIG_FILE:-$CONFIG_DIR/sipserver.conf}"
BIN_DIR="${BIN_DIR:-$INSTALL_ROOT/bin}"
DATA_DIR="${DATA_DIR:-/var/lib/sipserver}"
LOG_DIR="${LOG_DIR:-/var/log/sipserver}"
SERVICE_FILE="${SERVICE_FILE:-/etc/systemd/system/${APP_NAME}.service}"
DEFAULTS_FILE="${DEFAULTS_FILE:-/etc/default/${APP_NAME}}"
SOURCE_BIN="${SOURCE_BIN:-./bin/sipserver}"
SOURCE_CONFIG="${SOURCE_CONFIG:-./config.example.json}"
SOURCE_UNIT="${SOURCE_UNIT:-./deploy/systemd/sipserver.service}"
SOURCE_ENV_EXAMPLE="${SOURCE_ENV_EXAMPLE:-./deploy/systemd/sipserver.env.example}"

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    echo "Run this script as root: sudo $0" >&2
    exit 1
  fi
}

require_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    echo "Required file not found: $path" >&2
    exit 1
  fi
}

ensure_group() {
  if ! getent group "$APP_GROUP" >/dev/null 2>&1; then
    groupadd --system "$APP_GROUP"
  fi
}

ensure_user() {
  if ! id -u "$APP_USER" >/dev/null 2>&1; then
    local shell="/usr/sbin/nologin"
    if [[ ! -x "$shell" ]]; then
      shell="/sbin/nologin"
    fi
    useradd \
      --system \
      --gid "$APP_GROUP" \
      --home-dir "$DATA_DIR" \
      --create-home \
      --shell "$shell" \
      "$APP_USER"
  fi
}

install_files() {
  install -d -m 0755 "$BIN_DIR" "$CONFIG_DIR"
  install -d -m 0755 "$DATA_DIR" "$LOG_DIR"

  install -m 0755 "$SOURCE_BIN" "$BIN_DIR/$APP_NAME"
  install -m 0644 "$SOURCE_CONFIG" "$CONFIG_FILE"
  install -m 0644 "$SOURCE_UNIT" "$SERVICE_FILE"

  if [[ ! -f "$DEFAULTS_FILE" ]]; then
    install -m 0644 "$SOURCE_ENV_EXAMPLE" "$DEFAULTS_FILE"
  fi

  chown -R "$APP_USER:$APP_GROUP" "$INSTALL_ROOT" "$DATA_DIR" "$LOG_DIR"
}

reload_service() {
  systemctl daemon-reload
  systemctl enable --now "$APP_NAME"
  systemctl status --no-pager "$APP_NAME" || true
}

require_root
require_file "$SOURCE_BIN"
require_file "$SOURCE_CONFIG"
require_file "$SOURCE_UNIT"
require_file "$SOURCE_ENV_EXAMPLE"

ensure_group
ensure_user
install_files
reload_service

echo "Installed $APP_NAME service."
echo "Binary: $BIN_DIR/$APP_NAME"
echo "Config: $CONFIG_FILE"
echo "Logs:   $LOG_DIR/sipserver.log"
