#!/usr/bin/env bash

set -euo pipefail

# Runtime/ lives at the repository root; this script lives in iosApp/Scripts, so the root is
# two levels up rather than one as in the upstream fork.
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source_root="$repository_root/Runtime/MobileShims/src/main/java"
compat_source_root="$repository_root/Runtime/MobileCompatShims/src/main/java"
compat_test_source_root="$repository_root/Runtime/MobileCompatShims/src/test/java"
build_root="$repository_root/Runtime/MobileShims/build"
compat_build_root="$repository_root/Runtime/MobileCompatShims/build"
compat_test_build_root="$repository_root/Runtime/MobileCompatShims/test-build"
output_root="$repository_root/Runtime/MobileShims/dist"
output_jar="$output_root/tachiaz-mobile-shims.jar"
output_cacerts="$output_root/cacerts"
compat_root="$repository_root/Runtime/ExtensionHost/compat"
compat_output_jar="$compat_root/000-tachiaz-mobile-compat-shims.jar"
aircompressor_jar="$compat_root/aircompressor-0.27.jar"
aircompressor_sha256="fdbef3137a28f63bb0cb93487803080ede746a4ec3d421e36c6f0c305c35e5e4"
if command -v shasum >/dev/null; then
    checksum=(shasum -a 256)
else
    checksum=(sha256sum)
fi

if [[ -n "${TACHIYOMIAZ_BUILD_JAVA_HOME:-}" ]]; then
    java_home="$TACHIYOMIAZ_BUILD_JAVA_HOME"
elif [[ -n "${TACHIAZ_BUILD_JAVA_HOME:-}" ]]; then
    java_home="$TACHIAZ_BUILD_JAVA_HOME"
elif [[ -n "${JAVA_HOME:-}" ]]; then
    java_home="$JAVA_HOME"
else
    echo "Set TACHIYOMIAZ_BUILD_JAVA_HOME or JAVA_HOME." >&2
    exit 1
fi

rm -rf \
    "$build_root" \
    "$compat_build_root" \
    "$compat_test_build_root" \
    "$output_root"
mkdir -p \
    "$build_root" \
    "$compat_build_root" \
    "$compat_test_build_root" \
    "$output_root" \
    "$compat_root"
if [[ ! -f "$aircompressor_jar" ]] ||
    ! echo "$aircompressor_sha256  $aircompressor_jar" | "${checksum[@]}" -c -
then
    command -v curl >/dev/null || {
        echo "curl is required to download Aircompressor." >&2
        exit 1
    }
    temporary_aircompressor="$aircompressor_jar.download"
    curl --fail --location --retry 3 --retry-all-errors \
        --output "$temporary_aircompressor" \
        "https://repo1.maven.org/maven2/io/airlift/aircompressor/0.27/aircompressor-0.27.jar"
    echo "$aircompressor_sha256  $temporary_aircompressor" |
        "${checksum[@]}" -c -
    mv "$temporary_aircompressor" "$aircompressor_jar"
fi
sources=()
while IFS= read -r source; do
    sources+=("$source")
done < <(find "$source_root" -name '*.java' -type f | sort)

"$java_home/bin/javac" \
    -source 8 \
    -target 8 \
    -encoding UTF-8 \
    -d "$build_root" \
    "${sources[@]}"
find "$build_root" -exec touch -t 198001010000 {} +
"$java_home/bin/jar" cMf "$output_jar" -C "$build_root" .

compat_sources=()
while IFS= read -r source; do
    compat_sources+=("$source")
done < <(find "$compat_source_root" -name '*.java' -type f | sort)
compat_classpath="$(
    find "$compat_root" -maxdepth 1 -type f \
        -name '*.jar' \
        ! -name '000-tachiaz-mobile-compat-shims.jar' \
        -print | sort | paste -sd: -
)"
if [[ -z "$compat_classpath" ]]; then
    echo "OkHttp/Okio compatibility JARs are missing from $compat_root." >&2
    exit 1
fi
"$java_home/bin/javac" \
    -source 8 \
    -target 8 \
    -encoding UTF-8 \
    -cp "$compat_classpath" \
    -d "$compat_build_root" \
    "${compat_sources[@]}"
find "$compat_build_root" -exec touch -t 198001010000 {} +
"$java_home/bin/jar" cMf "$compat_output_jar" \
    -C "$compat_build_root" .
compat_test_sources=()
while IFS= read -r source; do
    compat_test_sources+=("$source")
done < <(find "$compat_test_source_root" -name '*.java' -type f | sort)
"$java_home/bin/javac" \
    -source 8 \
    -target 8 \
    -encoding UTF-8 \
    -cp "$compat_output_jar:$compat_classpath" \
    -d "$compat_test_build_root" \
    "${compat_test_sources[@]}"
"$java_home/bin/java" \
    -cp "$compat_output_jar:$compat_classpath:$compat_test_build_root" \
    app.tachiaz.runtime.MobileZstdShimTest
"$java_home/bin/java" \
    -cp "$compat_output_jar:$compat_classpath:$compat_test_build_root" \
    app.tachiaz.runtime.AndroidCompatibilitySurfaceTest
"$java_home/bin/java" \
    -cp "$compat_output_jar:$compat_classpath:$compat_test_build_root" \
    app.tachiaz.runtime.NativeRegistrationDescriptorTest \
    "$repository_root/iosApp/Packages/TachiJVMRunner/Sources/CJVMBridge/MobileNativeBridge.cpp"
if [[ ! -f "$java_home/lib/security/cacerts" ]]; then
    echo "JDK trust store is missing: $java_home/lib/security/cacerts" >&2
    exit 1
fi
cp "$java_home/lib/security/cacerts" "$output_cacerts"

echo "$output_jar"
echo "$compat_output_jar"
