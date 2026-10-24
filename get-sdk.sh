#!/usr/bin/env bash
# Завантажує офіційні AAR FitCloudPro SDK у app/libs (потрібно перед збиранням).
set -euo pipefail
cd "$(dirname "$0")"
SDK_REF="${SDK_REF:-master}"
TMP="$(mktemp -d)"
git clone -q --depth 1 --branch "$SDK_REF" https://github.com/htangsmart/FitCloudPro-SDK-Android "$TMP/sdk"
mkdir -p app/libs
cp "$TMP/sdk/libs/sdk-base-v3.0.2.7.aar" "$TMP/sdk/libs/sdk-fitcloud-v3.0.2.7.aar" app/libs/
cp "$TMP/sdk/libs/ext/ext-realtek-dfu-1.0.4.aar" "$TMP/sdk/libs/ext/ext-realtek-bbpro-1.0.4.aar" "$TMP/sdk/libs/ext/ext-realtek-file-1.0.4.aar" app/libs/
rm -rf "$TMP"
ls -1 app/libs
