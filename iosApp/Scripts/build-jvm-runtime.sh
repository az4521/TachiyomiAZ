#!/usr/bin/env bash
#
# Builds the JVM runtime jars from source and stages them where the Xcode build
# picks them up.
#
# These jars used to arrive as prebuilt binaries with no source in this repo, copied
# from a checkout of the upstream fork. That is how the SchaleNetwork WebView fix went
# missing: the Java source had it, our copy of the jar predated it, and nothing could
# tell -- there was no source to compare against and no build to rerun.
#
# Run this after changing anything under Runtime/.

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd "$script_dir/../.." && pwd)"
vendor="$repository_root/iosApp/Vendor/jars"

bash "$script_dir/build-mobile-shims.sh"
bash "$script_dir/build-extension-host.sh"

mkdir -p "$vendor/compat"
cp "$repository_root/Runtime/MobileShims/dist/tachiaz-mobile-shims.jar" "$vendor/"
cp "$repository_root/Runtime/ExtensionHost/dist/tachiaz-extension-host.jar" "$vendor/"
cp "$repository_root/Runtime/ExtensionHost/compat/"*.jar "$vendor/compat/"

echo
echo "Staged into $vendor:"
echo "  tachiaz-mobile-shims.jar"
echo "  tachiaz-extension-host.jar"
echo "  compat jars: $(ls "$vendor/compat" | wc -l | tr -d ' ')"
echo
echo "Run iosApp/Scripts/stage-jvm-resources.sh next, or just build the app."
