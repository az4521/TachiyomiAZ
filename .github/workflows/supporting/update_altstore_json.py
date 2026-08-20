#!/usr/bin/env python3

import argparse
import io
import json
import plistlib
import re
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path


REPOSITORY = "az4521/TachiyomiAZ"
DEFAULT_JSON = Path(".github/workflows/supporting/altstore/apps.json")


def github_request(url):
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "TachiyomiAZ-AltStore-Source",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request) as response:
        return response.read()


def fetch_release(tag):
    encoded_tag = urllib.parse.quote(tag, safe="")
    payload = github_request(
        f"https://api.github.com/repos/{REPOSITORY}/releases/tags/{encoded_tag}"
    )
    return json.loads(payload)


def select_ipa_asset(release):
    assets = [
        asset
        for asset in release.get("assets", [])
        if asset["name"].endswith(".ipa")
    ]
    if not assets:
        raise ValueError(f"Release {release.get('tag_name', '')!r} has no IPA asset")
    return assets[0]


def read_ipa_metadata(ipa_data):
    with zipfile.ZipFile(io.BytesIO(ipa_data), "r") as archive:
        plist_path = next(
            (
                name
                for name in archive.namelist()
                if name.startswith("Payload/")
                and name.count("/") == 2
                and name.endswith(".app/Info.plist")
            ),
            None,
        )
        if plist_path is None:
            raise FileNotFoundError("The IPA does not contain an application Info.plist")
        with archive.open(plist_path) as plist_file:
            return plistlib.loads(plist_file.read())


def release_description(text):
    text = re.sub(r"<[^>]+>", "", text or "")
    text = re.sub(r"^#{1,6}\s*", "", text, flags=re.MULTILINE)
    text = text.replace("**", "").replace("`", '"').strip()
    return text or "Automated build from the latest TachiyomiAZ iOS source."


def parse_date(value):
    if not value:
        return datetime.now(timezone.utc).strftime("%Y-%m-%d")
    return datetime.fromisoformat(value.replace("Z", "+00:00")).strftime("%Y-%m-%d")


def update_source(json_path, ipa_data, download_url, date, description):
    data = json.loads(json_path.read_text(encoding="utf-8"))
    if not data.get("apps"):
        raise ValueError("AltStore source has no app entry")

    metadata = read_ipa_metadata(ipa_data)
    bundle_id = metadata.get("CFBundleIdentifier")
    expected_bundle_id = data["apps"][0]["bundleIdentifier"]
    if bundle_id != expected_bundle_id:
        raise ValueError(
            f"IPA bundle identifier {bundle_id!r} does not match {expected_bundle_id!r}"
        )

    version = metadata.get("CFBundleShortVersionString")
    build = metadata.get("CFBundleVersion")
    if not version or not build:
        raise ValueError("IPA is missing version or build metadata")

    data["apps"][0]["versions"] = [
        {
            "version": str(version),
            "buildVersion": str(build),
            "date": date,
            "localizedDescription": description,
            "downloadURL": download_url,
            "size": len(ipa_data),
            "minOSVersion": str(metadata.get("MinimumOSVersion", "15.0")),
        }
    ]
    json_path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="Generate the TachiyomiAZ AltStore source")
    parser.add_argument("--json", type=Path, default=DEFAULT_JSON)
    parser.add_argument("--tag", default="nightly")
    parser.add_argument("--ipa", type=Path)
    parser.add_argument("--download-url")
    parser.add_argument("--date")
    parser.add_argument("--description")
    args = parser.parse_args()

    if args.ipa:
        if not args.download_url:
            parser.error("--download-url is required with --ipa")
        ipa_data = args.ipa.read_bytes()
        download_url = args.download_url
        date = parse_date(args.date)
        description = release_description(args.description)
    else:
        release = fetch_release(args.tag)
        asset = select_ipa_asset(release)
        ipa_data = github_request(asset["browser_download_url"])
        download_url = asset["browser_download_url"]
        date = parse_date(release.get("published_at"))
        description = release_description(release.get("body"))

    update_source(args.json, ipa_data, download_url, date, description)
    print(f"Updated {args.json}")


if __name__ == "__main__":
    main()
