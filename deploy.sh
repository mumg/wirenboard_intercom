#!/bin/zsh
~/Library/Android/sdk/platform-tools/adb disconnect
# bedroom
~/Library/Android/sdk/platform-tools/adb connect 192.168.7.172
~/Library/Android/sdk/platform-tools/adb push app_config_bedroom.json /sdcard/app_config.json
~/Library/Android/sdk/platform-tools/adb shell cp /sdcard/app_config.json /sdcard/Android/data/net.muratov.intercom/files/app_config.json
~/Library/Android/sdk/platform-tools/adb shell chmod 666 /sdcard/Android/data/net.muratov.intercom/files/app_config.json
~/Library/Android/sdk/platform-tools/adb install -r app/release/app-arm64-v8a-release.apk
~/Library/Android/sdk/platform-tools/adb shell am start -n net.muratov.intercom/.MainActivity
~/Library/Android/sdk/platform-tools/adb disconnect

# entrance
~/Library/Android/sdk/platform-tools/adb connect 192.168.7.218
~/Library/Android/sdk/platform-tools/adb push app_config_entrance.json /sdcard/app_config.json
~/Library/Android/sdk/platform-tools/adb shell cp /sdcard/app_config.json /sdcard/Android/data/net.muratov.intercom/files/app_config.json
~/Library/Android/sdk/platform-tools/adb shell chmod 666 /sdcard/Android/data/net.muratov.intercom/files/app_config.json
~/Library/Android/sdk/platform-tools/adb install -r app/release/app-arm64-v8a-release.apk
~/Library/Android/sdk/platform-tools/adb shell am start -n net.muratov.intercom/.MainActivity
~/Library/Android/sdk/platform-tools/adb disconnect


