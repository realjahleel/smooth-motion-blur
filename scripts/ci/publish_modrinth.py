"""Publish built jars to Modrinth (API v2). Idempotent: existing version numbers are skipped.

  python3 scripts/ci/publish_modrinth.py --target 26.3 [--dry-run]
  python3 scripts/ci/publish_modrinth.py --all [--dry-run]

Version number "<mod_version>+<id>", changelog = the "## <mod_version>" section of CHANGELOG.md.
Needs the env var MODRINTH_TOKEN unless --dry-run.
"""
import argparse
import json
import os
import re
import sys
import urllib.error
import uuid
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

API = "https://api.modrinth.com/v2"
PROJECT_ID = "M5szsQT4"
FABRIC_API_ID = "P7dR8mSH"
CHANGELOG = common.ROOT / "CHANGELOG.md"


def token(dry_run):
    tok = os.environ.get("MODRINTH_TOKEN", "").strip()
    if not tok and not dry_run:
        raise SystemExit(
            "ERROR: MODRINTH_TOKEN is not set.\n"
            "Create a Modrinth PAT (scopes VERSION_CREATE, VERSION_WRITE, PROJECT_READ) at "
            "https://modrinth.com/settings/pats and store it as the repository secret MODRINTH_TOKEN.")
    return tok


def changelog_section(mod_version, text=None):
    """Body of '## <mod_version>' up to the next '## ' heading, or None."""
    if text is None:
        text = CHANGELOG.read_text(encoding="utf-8") if CHANGELOG.is_file() else ""
    m = re.search(rf"^##\s+\[?{re.escape(mod_version)}\]?[^\n]*\n(.*?)(?=^##\s|\Z)", text, re.M | re.S)
    return m.group(1).strip() if m else None


def version_payload(target, mod_version, changelog):
    return {
        "project_id": PROJECT_ID,
        "version_number": f"{mod_version}+{target['id']}",
        "name": f"Smooth Motion Blur {mod_version} for {target['id']}",
        "changelog": changelog,
        "game_versions": list(target["game_versions"]),
        "loaders": ["fabric"],
        "version_type": target["version_type"],
        "dependencies": [{"project_id": FABRIC_API_ID, "dependency_type": "required"}],
        "featured": False,
        "status": "listed",
        "file_parts": ["file"],
        "primary_file": "file",
    }


def existing_versions():
    """{version_number: version object} of the project (public read, no token needed)."""
    data = common.get_json(f"{API}/project/{PROJECT_ID}/version?include_changelog=false")
    return {v["version_number"]: v for v in data}


def multipart(payload, jar):
    boundary = "----smb" + uuid.uuid4().hex
    parts = [
        f'--{boundary}\r\nContent-Disposition: form-data; name="data"\r\n'
        f"Content-Type: application/json\r\n\r\n".encode() + json.dumps(payload).encode() + b"\r\n",
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{jar.name}"\r\n'
        f"Content-Type: application/java-archive\r\n\r\n".encode() + jar.read_bytes() + b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ]
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def publish_target(target, dry_run=False, changelog_override=None, known=None):
    """Returns 'skipped', 'dry-run' or 'published'."""
    props = common.target_properties(target)
    mod_version = props["mod_version"]
    number = f"{mod_version}+{target['id']}"
    known = existing_versions() if known is None else known
    if number in known:
        print(f"[{target['id']}] {number} already on Modrinth (id {known[number]['id']}), skipping")
        return "skipped"

    changelog = changelog_override or changelog_section(mod_version)
    if not changelog:
        print(f"WARNING: CHANGELOG.md has no '## {mod_version}' section, using a generic changelog")
        changelog = f"Smooth Motion Blur {mod_version} for Minecraft {', '.join(target['game_versions'])}."
    payload = version_payload(target, mod_version, changelog)
    jar = common.find_jar(common.ROOT / target["dir"], props)

    if dry_run:
        print(f"[{target['id']}] DRY RUN, nothing sent:")
        print(f"  POST {API}/version  (multipart: data + file)")
        print("  Authorization: <MODRINTH_TOKEN>  User-Agent: " + common.USER_AGENT)
        print("  data = " + json.dumps(payload, indent=2).replace("\n", "\n  "))
        print(f"  file = {jar.relative_to(common.ROOT) if jar else '(jar not built yet)'}"
              + (f" ({jar.stat().st_size} bytes)" if jar else ""))
        return "dry-run"

    if jar is None:
        raise SystemExit(f"ERROR: no jar for {target['id']} {mod_version} in {target['dir']}/build/libs, build first")
    body, ctype = multipart(payload, jar)
    try:
        _, res = common.http("POST", f"{API}/version", data=body,
                             headers={"Authorization": token(False), "Content-Type": ctype}, timeout=300)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"ERROR: Modrinth rejected {number}: HTTP {e.code} {e.read().decode(errors='replace')}")
    print(f"[{target['id']}] published {number} -> https://modrinth.com/mod/smooth-motion-blur/version/{res['id']}")
    return "published"


def add_game_version(target, game_version, dry_run=False):
    """PATCH the existing Modrinth version of this target so it also lists game_version."""
    props = common.target_properties(target)
    number = f"{props['mod_version']}+{target['id']}"
    version = existing_versions().get(number)
    if version is None:
        print(f"[{target['id']}] {number} not on Modrinth yet, nothing to patch")
        return False
    if game_version in version["game_versions"]:
        print(f"[{target['id']}] {number} already lists {game_version}")
        return False
    new = version["game_versions"] + [game_version]
    url = f"{API}/version/{version['id']}"
    if dry_run:
        print(f"DRY RUN: PATCH {url} {json.dumps({'game_versions': new})}")
        return True
    try:
        common.http("PATCH", url, data=json.dumps({"game_versions": new}).encode(),
                    headers={"Authorization": token(False), "Content-Type": "application/json"})
    except urllib.error.HTTPError as e:
        raise SystemExit(f"ERROR: Modrinth PATCH {number} failed: HTTP {e.code} {e.read().decode(errors='replace')}")
    print(f"[{target['id']}] {number} now lists {game_version}")
    return True


def build_target(target):
    """Plain gradle build with the JDK the target needs (setup-java exports JAVA_HOME_<n>_X64)."""
    env = dict(os.environ)
    jdk = os.environ.get(f"JAVA_HOME_{target['java']}_X64")
    if jdk:
        env["JAVA_HOME"] = jdk
    code, _ = common.run_logged([*common.gradlew(), "-p", target["dir"], "build"], env=env)
    if code != 0:
        raise SystemExit(f"ERROR: build of {target['id']} failed (exit {code})")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sel = ap.add_mutually_exclusive_group(required=True)
    sel.add_argument("--target", action="append", help="target id from versions.json (repeatable)")
    sel.add_argument("--all", action="store_true", help="every target in versions.json")
    ap.add_argument("--dry-run", action="store_true", help="print the requests, send nothing")
    ap.add_argument("--changelog", help="override the changelog text")
    ap.add_argument("--build-missing", action="store_true",
                    help="gradle-build targets that are not on Modrinth yet and have no jar")
    args = ap.parse_args()

    token(args.dry_run)  # fail before doing anything else
    manifest = common.load_manifest()
    targets = manifest["targets"] if args.all else [common.find_target(manifest, t) for t in args.target]
    tag = os.environ.get("GITHUB_REF_NAME", "")
    known = existing_versions()
    for t in targets:
        mv = common.target_properties(t)["mod_version"]
        if tag.startswith("v") and tag[1:] != mv:
            print(f"WARNING: tag {tag} but {t['dir']} has mod_version {mv}")
        if args.build_missing and f"{mv}+{t['id']}" not in known \
                and common.find_jar(common.ROOT / t["dir"], common.target_properties(t)) is None:
            build_target(t)
        publish_target(t, args.dry_run, args.changelog, known)


if __name__ == "__main__":
    main()
