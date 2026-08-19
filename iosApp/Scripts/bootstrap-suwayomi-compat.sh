#!/usr/bin/env bash

set -euo pipefail

# Runtime/ and Vendor/ sit outside Scripts/ in this layout: Scripts is under iosApp,
# the repository root is two levels up.
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
build_root="$repository_root/Runtime/ExtensionHost/build/suwayomi"
checkout_root="${TACHIAZ_SUWAYOMI_SOURCE:-$build_root/source}"
runtime_root="$build_root/runtime"
output_root="$repository_root/Runtime/ExtensionHost/compat"
copy_init="$repository_root/iosApp/Scripts/gradle/copy-suwayomi-runtime.init.gradle"
ios_runtime_patch="$repository_root/iosApp/Scripts/patches/suwayomi-ios-runtime.patch"

suwayomi_repository="https://github.com/Suwayomi/Suwayomi-Server.git"
suwayomi_commit="eb2dc0b19a9571b27c02bebc5c883e404b7bd7fb"

if [[ -n "${TACHIYOMIAZ_BUILD_JAVA_HOME:-}" ]]; then
    java_home="$TACHIYOMIAZ_BUILD_JAVA_HOME"
elif [[ -n "${TACHIAZ_BUILD_JAVA_HOME:-}" ]]; then
    java_home="$TACHIAZ_BUILD_JAVA_HOME"
elif [[ -n "${JAVA_HOME:-}" ]]; then
    java_home="$JAVA_HOME"
else
    echo "Set TACHIYOMIAZ_BUILD_JAVA_HOME or JAVA_HOME to JDK 21+." >&2
    exit 1
fi

if [[ ! -x "$java_home/bin/java" ]]; then
    echo "Java is missing or not executable: $java_home/bin/java" >&2
    exit 1
fi

java_major="$("$java_home/bin/java" -version 2>&1 |
    sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
if [[ -z "$java_major" || "$java_major" -lt 21 ]]; then
    echo "The Suwayomi compatibility build requires JDK 21+." >&2
    exit 1
fi

if [[ -z "${TACHIAZ_SUWAYOMI_SOURCE:-}" ]]; then
    if [[ ! -d "$checkout_root/.git" ]]; then
        mkdir -p "$build_root"
        git clone --filter=blob:none --no-checkout \
            "$suwayomi_repository" "$checkout_root"
    fi
    git -C "$checkout_root" fetch --depth 1 origin "$suwayomi_commit"
    git -C "$checkout_root" checkout --detach "$suwayomi_commit"
    git -C "$checkout_root" reset --hard "$suwayomi_commit"
    git -C "$checkout_root" clean -fd
else
    actual_commit="$(git -C "$checkout_root" rev-parse HEAD)"
    if [[ "$actual_commit" != "$suwayomi_commit" ]]; then
        echo "TACHIAZ_SUWAYOMI_SOURCE must be at $suwayomi_commit." >&2
        echo "Current commit: $actual_commit" >&2
        exit 1
    fi
fi

if git -C "$checkout_root" apply --check "$ios_runtime_patch" 2>/dev/null; then
    git -C "$checkout_root" apply "$ios_runtime_patch"
elif ! git -C "$checkout_root" apply --reverse --check \
    "$ios_runtime_patch" 2>/dev/null
then
    echo "The iOS compatibility patch does not apply cleanly." >&2
    exit 1
fi

rm -rf "$runtime_root"
mkdir -p "$runtime_root"

GRADLE_OPTS="${GRADLE_OPTS:--Xmx4g -Dkotlin.daemon.jvm.options=-Xmx4g}" \
JAVA_HOME="$java_home" \
    "$checkout_root/gradlew" \
    -p "$checkout_root" \
    :server:copyTachiazRuntime \
    --no-daemon \
    --max-workers=2 \
    --init-script "$copy_init" \
    "-PtachiazRuntimeOutput=$runtime_root"

runtime_jars=(
    AndroidCompat-1.0.jar
    Config-1.0.jar
    Kotlin-Multiplatform-AppDirs-jvm-1.1.0.jar
    android-jar-1.0.0.jar
    annotations-23.0.0.jar
    config-1.4.9.jar
    config4k-0.7.0.jar
    core-jvm-1.0.1.jar
    dec-0.1.2.jar
    injekt-koin-ee267b2e27.jar
    jsoup-1.23.1.jar
    koin-core-jvm-4.2.2.jar
    kotlin-logging-jvm-8.0.4.jar
    kotlin-reflect-2.4.10.jar
    kotlin-stdlib-2.4.10.jar
    kotlin-stdlib-jdk7-2.4.10.jar
    kotlin-stdlib-jdk8-2.4.10.jar
    kotlinx-coroutines-core-jvm-1.11.0.jar
    kotlinx-coroutines-jdk8-1.11.0.jar
    kotlinx-serialization-core-jvm-1.11.0.jar
    kotlinx-serialization-json-jvm-1.11.0.jar
    kotlinx-serialization-json-okio-jvm-1.11.0.jar
    kotlinx-serialization-protobuf-jvm-1.11.0.jar
    logging-interceptor-5.4.0.jar
    multiplatform-settings-jvm-1.3.0.jar
    multiplatform-settings-serialization-jvm-1.3.0.jar
    okhttp-brotli-5.4.0.jar
    okhttp-jvm-5.4.0.jar
    okhttp-zstd-5.4.0.jar
    okio-jvm-3.18.1.jar
    rxjava-1.3.8.jar
    serialization-jvm-1.0.1.jar
    server-1.0.jar
    slf4j-api-2.0.18.jar
    zstd-kmp-jvm-0.4.0.jar
    zstd-kmp-okio-jvm-0.4.0.jar
)

rm -rf "$output_root"
mkdir -p "$output_root"
for jar_name in "${runtime_jars[@]}"; do
    source_path="$runtime_root/$jar_name"
    if [[ ! -f "$source_path" ]]; then
        echo "Pinned Suwayomi build did not produce $jar_name." >&2
        exit 1
    fi
    cp "$source_path" "$output_root/$jar_name"
done

du -sh "$output_root"
echo "Suwayomi compatibility JARs are ready at $output_root"
