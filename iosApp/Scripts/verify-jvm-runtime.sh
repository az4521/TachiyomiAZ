#!/usr/bin/env bash
#
# Checks that the committed JVM runtime jars match what Runtime/ actually builds.
#
# This exists because they once did not. The SchaleNetwork WebView fix changed
# IOSWebViewProviderFactory.java, and the jar we shipped predated it -- a jar is opaque,
# so nothing could tell, and the source it was built from was not even in the repository.
# Now that it is, the two can still drift the moment someone edits the source without
# rebuilding, or rebuilds without committing. This catches that.
#
# Compares class-by-class rather than by jar checksum: a jar embeds timestamps, so two
# builds of identical source differ as files while being identical as code.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

committed_host="$repository_root/Runtime/ExtensionHost/dist/tachiaz-extension-host.jar"
committed_shims="$repository_root/Runtime/MobileShims/dist/tachiaz-mobile-shims.jar"

# Keep the committed jars; the build overwrites them in place.
mkdir -p "$work/committed"
cp "$committed_host" "$work/committed/host.jar"
cp "$committed_shims" "$work/committed/shims.jar"
cp "$repository_root/Runtime/ExtensionHost/compat/000-tachiaz-mobile-compat-shims.jar" \
   "$work/committed/compat.jar" 2>/dev/null || true

bash "$script_dir/build-mobile-shims.sh" >/dev/null
bash "$script_dir/build-extension-host.sh" >/dev/null

status=0

compare_jar() {
    local name="$1" committed="$2" rebuilt="$3"
    [[ -f "$committed" ]] || { echo "no committed $name to compare"; return 0; }

    rm -rf "$work/a" "$work/b"
    mkdir -p "$work/a" "$work/b"
    (cd "$work/a" && unzip -qo "$committed")
    (cd "$work/b" && unzip -qo "$rebuilt")

    local differed=0
    while IFS= read -r class; do
        if ! cmp -s "$work/a/$class" "$work/b/$class"; then
            echo "  $class"
            differed=1
        fi
    done < <(cd "$work/b" && find . -name '*.class' | sed 's|^\./||')

    if [[ $differed -eq 1 ]]; then
        echo "$name is stale -- the classes above differ from what Runtime/ builds."
        status=1
    else
        echo "$name matches its source."
    fi
}

compare_jar "tachiaz-extension-host.jar" "$work/committed/host.jar" "$committed_host"
compare_jar "tachiaz-mobile-shims.jar" "$work/committed/shims.jar" "$committed_shims"
compare_jar "000-tachiaz-mobile-compat-shims.jar" "$work/committed/compat.jar" \
    "$repository_root/Runtime/ExtensionHost/compat/000-tachiaz-mobile-compat-shims.jar"

if [[ $status -ne 0 ]]; then
    echo
    echo "Run iosApp/Scripts/build-jvm-runtime.sh and commit the result."
fi
exit $status
