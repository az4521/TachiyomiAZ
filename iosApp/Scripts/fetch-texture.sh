#!/bin/bash
# Fetches the Texture (AsyncDisplayKit) xcframework the webtoon reader renders through.
#
# Declared as a Swift package this is a binaryTarget, and SPM's artifact download for it hangs
# indefinitely on this machine -- the checkout succeeds, the artifact directory stays empty, and
# the build sits at "Resolve Package Graph" forever. Fetching it directly is reliable, and the
# checksum below is the one in the package manifest, so this installs the same bits SPM would.
set -euo pipefail

VERSION="3.1.1"
CHECKSUM="fec2ec9d60a93627b3a19c845aa5d7547ec8f43f1065baf8f5abbdd11ef4681d"
URL="https://github.com/Skittyblock/Texture/releases/download/${VERSION}/AsyncDisplayKit.xcframework.zip"
DEST="$(cd "$(dirname "$0")/.." && pwd)/Vendor"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "Fetching Texture ${VERSION}..."
curl -fsSL -o "$tmp/AsyncDisplayKit.xcframework.zip" "$URL"

actual="$(shasum -a 256 "$tmp/AsyncDisplayKit.xcframework.zip" | cut -d' ' -f1)"
if [ "$actual" != "$CHECKSUM" ]; then
    echo "Checksum mismatch: expected $CHECKSUM, got $actual" >&2
    exit 1
fi

rm -rf "$DEST/AsyncDisplayKit.xcframework"
mkdir -p "$DEST"
unzip -q "$tmp/AsyncDisplayKit.xcframework.zip" -d "$DEST"
echo "Installed to $DEST/AsyncDisplayKit.xcframework"
