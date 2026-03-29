#!/usr/bin/env bash
set -euo pipefail

APP_NAME="${APP_NAME:-sipserver}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script as root: sudo $0" >&2
  exit 1
fi

systemctl daemon-reload
systemctl restart "$APP_NAME"
systemctl status --no-pager "$APP_NAME" || true
