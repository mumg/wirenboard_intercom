#!/bin/zsh
set -euo pipefail

SCRIPT_DIR=${0:A:h}
SCRIPT_NAME=${0:t}
APK="$SCRIPT_DIR/app/build/outputs/apk/release/app-arm64-v8a-release.apk"
DEVICE=""

read_local_property() {
    local key=$1
    local properties_file="$SCRIPT_DIR/local.properties"
    local value

    [[ -f "$properties_file" ]] || return 1
    value=$(awk -F= -v key="$key" '
        $1 == key {
            sub(/^[^=]*=/, "")
            print
            exit
        }
    ' "$properties_file")
    [[ -n "$value" ]] || return 1
    value=${value//\\:/:}
    value=${value//\\ / }
    print -r -- "$value"
}

resolve_android_sdk_dir() {
    local sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}

    if [[ -z "$sdk_dir" ]]; then
        sdk_dir=$(read_local_property sdk.dir || true)
    fi
    [[ -n "$sdk_dir" && -d "$sdk_dir" ]] || return 1
    print -r -- "$sdk_dir"
}

resolve_adb() {
    local sdk_dir=$1
    local executable

    if [[ -n "${INTERCOM_ADB:-}" ]]; then
        print -r -- "$INTERCOM_ADB"
    elif executable=$(command -v adb 2>/dev/null); then
        print -r -- "$executable"
    elif [[ -n "$sdk_dir" && -x "$sdk_dir/platform-tools/adb" ]]; then
        print -r -- "$sdk_dir/platform-tools/adb"
    else
        print -u2 "adb not found. Add it to PATH, set INTERCOM_ADB, or configure sdk.dir in local.properties."
        return 1
    fi
}

resolve_apksigner() {
    local sdk_dir=$1
    local executable
    local -a candidates

    if [[ -n "${INTERCOM_APKSIGNER:-}" ]]; then
        print -r -- "$INTERCOM_APKSIGNER"
    elif executable=$(command -v apksigner 2>/dev/null); then
        print -r -- "$executable"
    elif [[ -n "$sdk_dir" ]]; then
        candidates=("$sdk_dir"/build-tools/*/apksigner(NOn))
        if (( ${#candidates} > 0 )); then
            print -r -- "${candidates[1]}"
        else
            print -u2 "apksigner not found in Android SDK build-tools."
            return 1
        fi
    else
        print -u2 "apksigner not found. Add it to PATH or set INTERCOM_APKSIGNER."
        return 1
    fi
}

resolve_keystore() {
    local configured_path
    local -a candidates

    if [[ -n "${INTERCOM_KEYSTORE_PATH:-}" ]]; then
        print -r -- "$INTERCOM_KEYSTORE_PATH"
        return
    fi
    configured_path=$(read_local_property intercom.keystore.path || true)
    if [[ -n "$configured_path" ]]; then
        print -r -- "$configured_path"
        return
    fi

    candidates=("$SCRIPT_DIR/android.jks")
    if [[ -n "${HOME:-}" ]]; then
        candidates+=("$HOME/android.jks")
    fi
    for configured_path in "${candidates[@]}"; do
        if [[ -f "$configured_path" ]]; then
            print -r -- "$configured_path"
            return
        fi
    done

    print -u2 "Keystore not found. Set INTERCOM_KEYSTORE_PATH or intercom.keystore.path in local.properties."
    return 1
}

usage() {
    cat <<EOF
Usage: $SCRIPT_NAME [-d bedroom|entrance]

Options:
  -d, --device DEVICE  Deploy only to the specified device.
  -h, --help           Show this help message.

Without --device, the application is deployed to all devices.

Tool paths are resolved from PATH, Android SDK environment variables, and
local.properties. Override them with INTERCOM_ADB, INTERCOM_APKSIGNER, and
INTERCOM_KEYSTORE_PATH when needed.
EOF
}

while (( $# > 0 )); do
    case "$1" in
        -d|--device)
            if (( $# < 2 )); then
                print -u2 "Missing value for $1"
                usage >&2
                exit 2
            fi
            DEVICE=$2
            shift 2
            ;;
        --device=*)
            DEVICE=${1#*=}
            if [[ -z "$DEVICE" ]]; then
                print -u2 "Missing value for --device"
                usage >&2
                exit 2
            fi
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            print -u2 "Unknown argument: $1"
            usage >&2
            exit 2
            ;;
    esac
done

case "$DEVICE" in
    ""|bedroom|entrance) ;;
    *)
        print -u2 "Unknown device: $DEVICE (expected bedroom or entrance)"
        exit 2
        ;;
esac

ANDROID_SDK_DIR=$(resolve_android_sdk_dir || true)
ADB=$(resolve_adb "$ANDROID_SDK_DIR")
APKSIGNER=$(resolve_apksigner "$ANDROID_SDK_DIR")
KEYSTORE=$(resolve_keystore)

if [[ ! -x "$ADB" ]]; then
    print -u2 "adb is not executable: $ADB"
    exit 1
fi
if [[ ! -x "$APKSIGNER" ]]; then
    print -u2 "apksigner is not executable: $APKSIGNER"
    exit 1
fi
if [[ ! -f "$KEYSTORE" ]]; then
    print -u2 "Keystore not found: $KEYSTORE"
    exit 1
fi

if [[ -z "${INTERCOM_KEYSTORE_PASSWORD:-}" ]]; then
    read -r -s "INTERCOM_KEYSTORE_PASSWORD?Keystore password: "
    print
fi
if [[ -z "${INTERCOM_KEY_ALIAS:-}" ]]; then
    read -r "INTERCOM_KEY_ALIAS?Key alias: "
fi
if [[ -z "$INTERCOM_KEY_ALIAS" ]]; then
    print -u2 "Key alias must not be empty"
    exit 1
fi
if [[ -z "${INTERCOM_KEY_PASSWORD:-}" ]]; then
    read -r -s "INTERCOM_KEY_PASSWORD?Key password (Enter = keystore password): "
    print
    INTERCOM_KEY_PASSWORD=${INTERCOM_KEY_PASSWORD:-$INTERCOM_KEYSTORE_PASSWORD}
fi
export INTERCOM_KEYSTORE_PATH="$KEYSTORE"
export INTERCOM_KEYSTORE_PASSWORD INTERCOM_KEY_ALIAS INTERCOM_KEY_PASSWORD INTERCOM_KEYSTORE_PATH

cd "$SCRIPT_DIR"
./gradlew :app:assembleRelease

if [[ ! -f "$APK" ]]; then
    print -u2 "Signed release APK was not created: $APK"
    exit 1
fi
"$APKSIGNER" verify --verbose "$APK"
print "Signed release APK: $APK"

deploy_device() {
    local address=$1
    local config_file=$2

    "$ADB" connect "$address"
    "$ADB" -s "$address" push "$config_file" /sdcard/app_config.json
    "$ADB" -s "$address" shell cp /sdcard/app_config.json /sdcard/Android/data/net.muratov.intercom/files/app_config.json
    "$ADB" -s "$address" shell chmod 666 /sdcard/Android/data/net.muratov.intercom/files/app_config.json
    "$ADB" -s "$address" install -r "$APK"
    "$ADB" -s "$address" shell am start -n net.muratov.intercom/.MainActivity
    "$ADB" disconnect "$address"
}

"$ADB" disconnect
if [[ -z "$DEVICE" || "$DEVICE" == bedroom ]]; then
    deploy_device 192.168.7.172 "$SCRIPT_DIR/app_config_bedroom.json"
fi
if [[ -z "$DEVICE" || "$DEVICE" == entrance ]]; then
    deploy_device 192.168.7.218 "$SCRIPT_DIR/app_config_entrance.json"
fi
