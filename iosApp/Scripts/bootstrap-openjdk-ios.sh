#!/usr/bin/env bash

set -euo pipefail

# Runtime/ and Vendor/ sit outside Scripts/ in this layout: Scripts is under iosApp,
# the repository root is two levels up.
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
vendor_root="$repository_root/iosApp/Vendor/jvm"
download_root="${TMPDIR:-/tmp}/tachiaz-openjdk-mobile"
framework_archive="$download_root/OpenJDK.xcframework.zip"
device_bundle_archive="$download_root/java_bundle-device.zip"
simulator_bundle_archive="$download_root/java_bundle-simulator.zip"
release_root="https://github.com/openjdk-mobile/ios-tools/releases/download/snapshot"
framework_sha256="ae8e22142e45e5c1e9e8e3541829f3cc00584658eb21eeeb0d3612e3fbaf7c9f"
device_bundle_sha256="37387a48bdd7f1ce1d3a38e88356e61288673c91b42ef27cf1b71d27e05cc54a"
simulator_bundle_sha256="b88598422fad02b0926267becfe9bd6b51e3e57d188ef524105223a4bf7cee90"

command -v curl >/dev/null || {
    echo "curl is required." >&2
    exit 1
}
command -v unzip >/dev/null || {
    echo "unzip is required." >&2
    exit 1
}
mkdir -p "$vendor_root" "$download_root"

download() {
    local name="$1"
    local destination="$2"
    if [[ ! -f "$destination" ]]; then
        curl \
            --fail \
            --location \
            --output "$destination" \
            "$release_root/$name"
    fi
}

checksum() {
    if command -v shasum >/dev/null; then
        shasum -a 256 "$1" | cut -d ' ' -f 1
    elif command -v sha256sum >/dev/null; then
        sha256sum "$1" | cut -d ' ' -f 1
    else
        echo "shasum or sha256sum is required." >&2
        exit 1
    fi
}

verify() {
    local archive="$1"
    local expected="$2"
    local actual
    actual="$(checksum "$archive")"
    if [[ "$actual" != "$expected" ]]; then
        echo "OpenJDK/mobile archive checksum mismatch: $archive" >&2
        echo "Expected: $expected" >&2
        echo "Actual:   $actual" >&2
        exit 1
    fi
}

download "OpenJDK.xcframework.zip" "$framework_archive"
download "java_bundle-device.zip" "$device_bundle_archive"
download "java_bundle-simulator.zip" "$simulator_bundle_archive"
verify "$framework_archive" "$framework_sha256"
verify "$device_bundle_archive" "$device_bundle_sha256"
verify "$simulator_bundle_archive" "$simulator_bundle_sha256"

rm -rf \
    "$vendor_root/OpenJDK.xcframework" \
    "$vendor_root/java_bundle" \
    "$vendor_root/java_bundle-device" \
    "$vendor_root/java_bundle-simulator"
unzip -q "$framework_archive" -d "$vendor_root"
unzip -q "$device_bundle_archive" -d "$vendor_root"
unzip -q "$simulator_bundle_archive" -d "$vendor_root"

if [[
    ! -d "$vendor_root/OpenJDK.xcframework" ||
    ! -f "$vendor_root/java_bundle-device/lib/modules" ||
    ! -f "$vendor_root/java_bundle-simulator/lib/modules"
]]; then
    echo "Downloaded archives do not have the expected iOS JVM layout." >&2
    exit 1
fi

echo "OpenJDK/mobile Zero runtime installed under $vendor_root"
