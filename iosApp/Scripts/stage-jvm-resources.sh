#!/usr/bin/env bash
#
# Stages the JVM runtime into the exact layout JVMRuntimeConfiguration.bundled() looks for:
#
#   java_bundle/                 the JDK image (device and simulator images differ)
#   tachiaz-extension-host.jar   the host that receives dispatch() requests
#   tachiaz-mobile-shims.jar     boot classpath shim, ahead of everything else
#   tachiaz-compat/*.jar         Suwayomi AndroidCompat and its dependencies
#
# The names are not arbitrary and not configurable -- the runtime resolves them by string
# against the app's resource directory, so anything else fails at boot rather than at build.
#
# Device and simulator need *different* java_bundle images, which is why this is a script and
# not a static resource list: the correct one is chosen per build, then copied under the single
# name the runtime expects.
#
#   ./Scripts/stage-jvm-resources.sh simulator   (default)
#   ./Scripts/stage-jvm-resources.sh device

set -euo pipefail

variant="${1:-simulator}"
case "$variant" in
    device|simulator) ;;
    *) echo "Usage: $0 [device|simulator]" >&2; exit 1 ;;
esac

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ios_app_root="$(cd "$script_dir/.." && pwd)"
vendor="$ios_app_root/Vendor"
staged="$ios_app_root/Resources/JVM"

source_bundle="$vendor/jvm/java_bundle-$variant"
if [[ ! -d "$source_bundle" ]]; then
    echo "Missing $source_bundle. Build the runtime first; see IOS_PORT.md." >&2
    exit 1
fi

rm -rf "$staged"
mkdir -p "$staged/tachiaz-compat"

# ditto rather than cp -R: the JDK image contains symlinks and the modules file, and ditto
# preserves the layout the VM expects without following links into duplicates.
ditto "$source_bundle" "$staged/java_bundle"
# Warn when a jar about to be staged is older than the source it is built from.
#
# This is the check that would have caught the SchaleNetwork fix shipping with only its Swift half:
# IOSWebViewProviderFactory.java had the fix, the jar being staged predated it, and nothing looked.
# Not fatal -- the compat jars are also produced by a long Suwayomi bootstrap, and failing a build
# over a timestamp would be worse than saying so -- but it is said loudly.
check_freshness() {
    local jar="$1" source_root="$2" label="$3"
    [[ -f "$jar" && -d "$source_root" ]] || return 0
    local newest
    newest="$(find "$source_root" -name '*.java' -newer "$jar" -print -quit 2>/dev/null)"
    if [[ -n "$newest" ]]; then
        echo "  WARNING: $label is older than its source (${newest#$repository_root/})." >&2
        echo "           Run iosApp/Scripts/build-jvm-runtime.sh to rebuild it." >&2
    fi
}

repository_root="$(cd "$ios_app_root/.." && pwd)"
check_freshness "$vendor/jars/compat/000-tachiaz-mobile-compat-shims.jar" \
    "$repository_root/Runtime/MobileCompatShims/src" "compat shims jar"
check_freshness "$vendor/jars/tachiaz-mobile-shims.jar" \
    "$repository_root/Runtime/MobileShims/src" "mobile shims jar"
check_freshness "$vendor/jars/tachiaz-extension-host.jar" \
    "$repository_root/Runtime/ExtensionHost/src" "extension host jar"

cp "$vendor/jars/tachiaz-extension-host.jar" "$staged/"
cp "$vendor/jars/tachiaz-mobile-shims.jar" "$staged/"
cp "$vendor/jars/compat/"*.jar "$staged/tachiaz-compat/"

echo "Staged $variant JVM runtime at $staged"
echo "  java_bundle:    $(du -sh "$staged/java_bundle" | cut -f1)"
echo "  compat jars:    $(ls "$staged/tachiaz-compat" | wc -l | tr -d ' ')"
