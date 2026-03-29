#!/usr/bin/env bash
set -euo pipefail

APP_NAME="${APP_NAME:-sipserver}"
SERVICE_FILE="${SERVICE_FILE:-/etc/systemd/system/${APP_NAME}.service}"
INSTALL_ROOT="${INSTALL_ROOT:-/opt/sipserver}"
DEFAULTS_FILE="${DEFAULTS_FILE:-/etc/default/${APP_NAME}}"
CONFIG_DIR="${CONFIG_DIR:-/etc/sipserver}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script as root: sudo $0" >&2
  exit 1
fi

if systemctl list-unit-files | grep -q "^${APP_NAME}.service"; then
  systemctl disable --now "$APP_NAME" || true
fi

rm -f "$SERVICE_FILE"
systemctl daemon-reload
systemctl reset-failed || true

echo "Service unit removed."
echo "Application files are still present in $INSTALL_ROOT"
echo "Configuration files are still present in $CONFIG_DIR"
echo "Environment file is still present in $DEFAULTS_FILE"
