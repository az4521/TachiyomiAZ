#!/usr/bin/env bash
#
# Checks that a committed JVM runtime jar matches what Runtime/ actually builds.
#
# Only tachiaz-extension-host.jar is checked, and that is a real limit worth stating.
#
# Three jars ship. Two of them -- tachiaz-mobile-shims.jar and the compat shims -- compile against
# the Suwayomi AndroidCompat runtime: 56MB of third-party build output that bootstrap-suwayomi-
# compat.sh produces by cloning and building Suwayomi-Server at a pinned commit. Neither this
# repository nor the fork commits that, so a clean checkout cannot build them without a long
# network-bound bootstrap. The extension host has no such dependency and builds from a bare clone.
#
# So this catches drift in the host jar and nothing else. What protects the other two is that
# their sources now live here and build-jvm-runtime.sh regenerates them, rather than their being
# copied in from another checkout -- which is how the SchaleNetwork fix went missing.
#
# Compares class-by-class rather than by jar checksum: a jar embeds timestamps, so two builds of
# identical source differ as files while being identical as code.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

committed="$repository_root/Runtime/ExtensionHost/dist/tachiaz-extension-host.jar"
if [[ ! -f "$committed" ]]; then
    echo "No committed tachiaz-extension-host.jar to compare against." >&2
    exit 1
fi

cp "$committed" "$work/committed.jar"
bash "$script_dir/build-extension-host.sh" >/dev/null

mkdir -p "$work/a" "$work/b"
(cd "$work/a" && unzip -qo "$work/committed.jar")
(cd "$work/b" && unzip -qo "$committed")

status=0
while IFS= read -r class; do
    if ! cmp -s "$work/a/$class" "$work/b/$class"; then
        echo "  $class"
        status=1
    fi
done < <(cd "$work/b" && find . -name '*.class' | sed 's|^\./||')

if [[ $status -ne 0 ]]; then
    echo "tachiaz-extension-host.jar is stale -- the classes above differ from what Runtime/ builds."
    echo "Run iosApp/Scripts/build-jvm-runtime.sh and commit the result."
else
    echo "tachiaz-extension-host.jar matches its source."
fi
exit $status
