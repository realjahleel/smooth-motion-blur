"""Daily auto-port to new Minecraft versions.

  python3 scripts/ci/auto_update.py           # --plan: read-only, prints what it would do
  python3 scripts/ci/auto_update.py --apply   # CI only: port, build, gametest, commit, push, publish

Picks the newest stable and the newest unstable Fabric game version (newer than 1.21.11)
that no target in versions.json covers, then:
  patch     26.3.1 for target 26.3: test the old folder with -P overrides, add it to game_versions
  snapshot  26.4-snapshot-5 / -pre-1 / -rc-1: update versions/26.4-snapshot (or create it)
  minor     26.4: create versions/26.4 from versions/26.4-snapshot (or the newest release)
"""
import argparse
import collections
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402
import publish_modrinth  # noqa: E402

FLOOR = "1.21.11"
FABRIC_META = "https://meta.fabricmc.net/v2"
MODRINTH = "https://api.modrinth.com/v2"
VERSION_RE = re.compile(r"^(\d+)\.(\d+)(?:\.(\d+))?(?:-(snapshot|pre|rc)-(\d+))?$")
STAGE_RANK = {"snapshot": 0, "pre": 1, "rc": 2, None: 3}
COPY_IGNORE = shutil.ignore_patterns("build", ".gradle", "run", "out", "bin", ".idea")


# ======================================================================
# pure decision logic (unit-tested offline in test_auto_update.py)
# ======================================================================

@dataclass(frozen=True)
class McVersion:
    raw: str
    major: int
    minor: int
    patch: int
    stage: str  # None for stable, else snapshot | pre | rc
    n: int

    @property
    def line(self):
        return f"{self.major}.{self.minor}"

    @property
    def stable(self):
        return self.stage is None

    @property
    def key(self):
        return (self.major, self.minor, self.patch, STAGE_RANK[self.stage], self.n)


def parse_version(v):
    m = VERSION_RE.match(v)
    if not m:
        return None
    major, minor, patch, stage, n = m.groups()
    return McVersion(v, int(major), int(minor), int(patch or 0), stage, int(n or 0))


def loader_semver(v):
    """Fabric Loader's normalized form: 26.4-snapshot-4 -> 26.4-alpha.4, 26.4-pre-1 -> 26.4-pre.1."""
    p = parse_version(v)
    if p is None or p.stable:
        return v
    stage = "alpha" if p.stage == "snapshot" else p.stage
    base = v.split("-", 1)[0]
    return f"{base}-{stage}.{p.n}"


def higher_loader(current, candidate):
    """Never downgrade the loader (a folder may already pin a newer beta loader)."""
    key = lambda s: [int(x) for x in re.findall(r"\d+", s.split("+")[0])]  # noqa: E731
    return candidate if key(candidate) > key(current) else current


def bump_patch(mod_version):
    parts = mod_version.split(".")
    parts[-1] = str(int(parts[-1]) + 1)
    return ".".join(parts)


def covered(manifest):
    return {gv for t in manifest["targets"] for gv in t["game_versions"]}


def target_key(target):
    keys = [parse_version(gv).key for gv in target["game_versions"] if parse_version(gv)]
    return max(keys) if keys else (0,)


def is_snapshot_target(target):
    return target["id"].endswith("-snapshot")


def release_target_for_line(manifest, line):
    for t in manifest["targets"]:
        if not is_snapshot_target(t) and any(
                (p := parse_version(gv)) and p.line == line for gv in t["game_versions"]):
            return t
    return None


def newer_than_floor(game_versions):
    """Fabric meta lists newest first; keep the entries above FLOOR that we can parse."""
    out = []
    for e in game_versions:
        if e["version"] == FLOOR:
            break
        p = parse_version(e["version"])
        if p and p.stable == bool(e.get("stable")):
            out.append(p)
    return out


def pick_candidates(game_versions, manifest):
    """(newest uncovered stable, newest unstable if uncovered) - either may be None."""
    versions = newer_than_floor(game_versions)
    done = covered(manifest)
    released_lines = {p.line for p in versions if p.stable}
    released_lines |= {parse_version(gv).line for t in manifest["targets"] if not is_snapshot_target(t)
                       for gv in t["game_versions"] if parse_version(gv)}

    stable = next((p for p in versions if p.stable and p.raw not in done), None)
    # only the very newest unstable of a still unreleased line counts; older ones are superseded
    unstable = next((p for p in versions if not p.stable and p.line not in released_lines), None)
    if unstable and unstable.raw in done:
        unstable = None
    return stable, unstable


@dataclass
class Action:
    kind: str                 # patch | snapshot | minor | skip
    version: str
    target_id: str = None     # target to create or update
    source_id: str = None     # folder to copy from (new targets only)
    remove_id: str = None     # snapshot target replaced by a release
    reason: str = ""
    new_target: bool = False

    def describe(self):
        if self.kind == "skip":
            return f"{self.version}: skip ({self.reason})"
        if self.kind == "patch":
            return f"{self.version}: stable patch of target {self.target_id}, test it there and add to game_versions"
        verb = f"create versions/{self.target_id} from versions/{self.source_id}" if self.new_target \
            else f"update versions/{self.target_id} in place"
        extra = f", then drop target {self.remove_id}" if self.remove_id else ""
        return f"{self.version}: {self.kind}, {verb}{extra}"


def classify(p, manifest):
    """Decide what to do with an uncovered version p (McVersion)."""
    ids = {t["id"]: t for t in manifest["targets"]}
    releases = [t for t in manifest["targets"] if not is_snapshot_target(t)]
    newest_release = max(releases, key=target_key)
    newest_any = max(manifest["targets"], key=target_key)

    if p.stable:
        existing = release_target_for_line(manifest, p.line)
        if existing:
            return Action("patch", p.raw, target_id=existing["id"])
        if p.key <= target_key(newest_release):
            return Action("skip", p.raw, reason=f"line {p.line} is older than target {newest_release['id']}")
        snap = ids.get(f"{p.line}-snapshot")
        return Action("minor", p.raw, target_id=p.line, new_target=True,
                      source_id=snap["id"] if snap else newest_release["id"],
                      remove_id=snap["id"] if snap else None)

    if release_target_for_line(manifest, p.line):
        return Action("skip", p.raw, reason=f"line {p.line} already has a release target")
    snap_id = f"{p.line}-snapshot"
    if snap_id in ids:
        return Action("snapshot", p.raw, target_id=snap_id)
    return Action("snapshot", p.raw, target_id=snap_id, new_target=True, source_id=newest_any["id"])


def new_label(old_label, old_mc, new_mc, old_id, new_id):
    if old_mc and old_mc in old_label:
        return old_label.replace(old_mc, new_mc)
    if old_label == old_id:
        return new_id
    return old_label


def rewrite_mc_dependency(dep, old_mc, new_mc, release=False):
    """Change the fabric.mod.json "minecraft" dependency the way it is already written."""
    if release:
        return f"~{new_mc}"
    for old, new in ((loader_semver(old_mc), loader_semver(new_mc)), (old_mc, new_mc)):
        if old in dep:
            return dep.replace(old, new)
    return dep  # a range that does not name the old version keeps working


def apply_to_manifest(manifest, action, game_version, source=None):
    """Mutates manifest for a successful snapshot/minor/patch action."""
    if action.kind == "patch":
        t = common.find_target(manifest, action.target_id)
        if game_version not in t["game_versions"]:
            t["game_versions"].append(game_version)
        return manifest
    if not action.new_target:
        common.find_target(manifest, action.target_id)["game_versions"] = [game_version]
        return manifest
    new = {
        "id": action.target_id,
        "dir": f"versions/{action.target_id}",
        "java": source["java"] if source else 25,
        "game_versions": [game_version],
        "version_type": "beta" if action.kind == "snapshot" else "release",
        "vulkanmod": None,
    }
    targets = [t for t in manifest["targets"] if t["id"] != action.remove_id]
    if action.kind == "snapshot":
        targets.append(new)
    else:  # releases go before the snapshot targets
        pos = next((i for i, t in enumerate(targets) if is_snapshot_target(t)), len(targets))
        targets.insert(pos, new)
    manifest["targets"] = targets
    return manifest


# ======================================================================
# network lookups
# ======================================================================

def fetch_game_versions():
    return common.get_json(f"{FABRIC_META}/versions/game")


def fabric_api_for(v):
    q = urllib.parse.urlencode({"game_versions": f'["{v}"]', "loaders": '["fabric"]'})
    data = common.get_json(f"{MODRINTH}/project/{publish_modrinth.FABRIC_API_ID}/version?{q}") or []
    data.sort(key=lambda x: x["date_published"], reverse=True)
    return data[0]["version_number"] if data else None


def loader_for(v):
    try:
        data = common.get_json(f"{FABRIC_META}/versions/loader/{urllib.parse.quote(v)}")
    except urllib.error.HTTPError:
        return None
    if not isinstance(data, list) or not data:
        return None
    stable = [e["loader"]["version"] for e in data if e["loader"].get("stable")]
    return stable[0] if stable else data[0]["loader"]["version"]


def modrinth_knows(v):
    return any(t["version"] == v for t in common.get_json(f"{MODRINTH}/tag/game_version"))


def resolve(v):
    """{'fabric_api': ..., 'loader': ...} or None (with a printed reason) if something is missing."""
    deps = {"fabric_api": fabric_api_for(v), "loader": loader_for(v)}
    missing = [k for k, val in deps.items() if not val]
    if not modrinth_knows(v):
        missing.append("modrinth game version tag")
    if missing:
        print(f"  {v}: waiting, missing {', '.join(missing)} (try again tomorrow)")
        return None
    print(f"  {v}: Fabric API {deps['fabric_api']}, Loader {deps['loader']}")
    return deps


# ======================================================================
# side effects (CI only)
# ======================================================================

def git(*args, check=True):
    print("+ git " + " ".join(args), flush=True)
    return subprocess.run(["git", *args], cwd=common.ROOT, check=check)


def fabric_mod_json(target_dir):
    hits = sorted(Path(target_dir).glob("src/*/resources/fabric.mod.json"))
    if not hits:
        raise RuntimeError(f"no fabric.mod.json under {target_dir}/src")
    return hits[0]


def prepare_folder(action, deps, manifest):
    """Copy (if new) and rewrite gradle.properties + fabric.mod.json. Returns the target dir."""
    v = action.version
    if action.new_target:
        src_t = common.find_target(manifest, action.source_id)
        src, dst = common.ROOT / src_t["dir"], common.ROOT / "versions" / action.target_id
        if dst.exists():
            raise RuntimeError(f"{dst} already exists but is not in versions.json")
        shutil.copytree(src, dst, ignore=COPY_IGNORE)
        old_id = src_t["id"]
    else:
        dst = common.ROOT / common.find_target(manifest, action.target_id)["dir"]
        old_id = action.target_id

    props_file = dst / "gradle.properties"
    text = props_file.read_text(encoding="utf-8")
    props = common.read_properties(props_file)
    old_mc = props["minecraft_version"]
    for k, val in (("minecraft_version", v), ("fabric_api_version", deps["fabric_api"]),
                   ("loader_version", higher_loader(props["loader_version"], deps["loader"])),
                   ("mod_version", bump_patch(props["mod_version"]))):
        text = common.set_property(text, k, val)
    if "mc_label" in props:
        text = common.set_property(text, "mc_label",
                                   new_label(props["mc_label"], old_mc, v, old_id, action.target_id))
    props_file.write_text(text, encoding="utf-8", newline="\n")

    fmj = fabric_mod_json(dst)
    raw = fmj.read_text(encoding="utf-8")
    raw = re.sub(r'("minecraft"\s*:\s*")([^"]*)(")',
                 lambda m: m.group(1) + rewrite_mc_dependency(m.group(2), old_mc, v, action.kind == "minor")
                 + m.group(3), raw, count=1)
    fmj.write_text(raw, encoding="utf-8", newline="\n")
    return dst


def commit_and_push(message):
    git("config", "user.name", "github-actions[bot]")
    git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    git("add", "-A", "versions.json", "versions")
    git("commit", "-m", message)
    git("pull", "--rebase", "origin", "main")
    git("push", "origin", "HEAD:main")


def report_failure(v, log, description):
    repo, tok = os.environ.get("GITHUB_REPOSITORY"), os.environ.get("GITHUB_TOKEN")
    tail = "\n".join(list(log)[-80:])
    run = ""
    if repo and os.environ.get("GITHUB_RUN_ID"):
        run = f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/{repo}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
    title = f"Auto-port to {v} failed"
    body = f"{description}\n\nRun: {run or '(local)'}\n\nLast 80 log lines:\n\n```\n{tail}\n```\n"
    if not (repo and tok):
        print(f"(no GITHUB_REPOSITORY/GITHUB_TOKEN, would open issue {title!r})\n{body}")
        return
    gh = {"Authorization": f"Bearer {tok}", "Accept": "application/vnd.github+json"}
    api = f"https://api.github.com/repos/{repo}"
    issues = common.get_json(f"{api}/issues?state=open&per_page=100", headers=gh)
    existing = next((i for i in issues if i["title"] == title and "pull_request" not in i), None)
    if existing:
        common.http("POST", f"{api}/issues/{existing['number']}/comments",
                    data=json.dumps({"body": body}).encode(), headers=gh)
        print(f"commented on issue #{existing['number']}")
    else:
        _, res = common.http("POST", f"{api}/issues", data=json.dumps({"title": title, "body": body}).encode(),
                             headers=gh)
        print(f"opened issue #{res['number']}")


def execute(action, deps, manifest):
    """Returns True on success. Never commits a failed port."""
    v = action.version
    log = collections.deque(maxlen=2000)
    if action.kind == "patch":
        t = common.find_target(manifest, action.target_id)
        ok = common.build_and_gametest(common.ROOT / t["dir"], log=log, overrides={
            "minecraft_version": v, "fabric_api_version": deps["fabric_api"]})
        if not ok:
            report_failure(v, log, f"Target `{t['id']}` did not build or pass the gametest on {v} "
                                   f"(Fabric API {deps['fabric_api']}). Nothing was committed.")
            return False
        common.save_manifest(apply_to_manifest(manifest, action, v))
        commit_and_push(f"Auto-port to {v}\n\nAdd {v} to target {t['id']} (built and gametested unchanged).")
        try:
            publish_modrinth.add_game_version(t, v)
        except SystemExit as e:
            report_failure(v, [str(e)], f"{v} was added to target {t['id']} and pushed, but the Modrinth PATCH failed.")
            return False
        return True

    source = common.find_target(manifest, action.source_id) if action.new_target else None
    dst = None
    try:
        dst = prepare_folder(action, deps, manifest)
        ok = common.build_and_gametest(dst, log=log)
    except Exception as e:  # noqa: BLE001 - every failure ends up in the issue
        log.append(f"ERROR: {e!r}")
        ok = False
    if not ok:
        git("checkout", "--", "versions.json", "versions", check=False)
        if action.new_target and dst and dst.exists():
            shutil.rmtree(dst)
        report_failure(v, log, f"{action.describe()}\n\nFabric API {deps['fabric_api']}, "
                               f"Loader {deps['loader']}. Nothing was committed.")
        return False

    apply_to_manifest(manifest, action, v, source)
    if action.remove_id:
        shutil.rmtree(common.ROOT / "versions" / action.remove_id)
    common.save_manifest(manifest)
    commit_and_push(f"Auto-port to {v}\n\n{action.describe()}\nFabric API {deps['fabric_api']}, "
                    f"Loader {deps['loader']}. Built and gametested headless.")
    try:
        publish_modrinth.publish_target(common.find_target(manifest, action.target_id),
                                        changelog_override=f"Automatic port to Minecraft {v}. No functional changes.")
    except SystemExit as e:
        report_failure(v, [str(e)], f"Port to {v} was committed, but publishing to Modrinth failed. "
                                    f"Re-run the publish workflow.")
        return False
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--plan", action="store_true", help="read-only (default)")
    mode.add_argument("--apply", action="store_true", help="port, commit, push and publish (CI)")
    ap.add_argument("--simulate", metavar="VERSION", action="append", default=[],
                    help="plan only: pretend VERSION is not in versions.json yet (exercises the live lookups)")
    args = ap.parse_args()
    if args.simulate and args.apply:
        ap.error("--simulate only works with --plan")

    if args.apply:
        publish_modrinth.token(dry_run=False)
        if subprocess.run(["git", "status", "--porcelain"], cwd=common.ROOT,
                          capture_output=True, text=True).stdout.strip():
            raise SystemExit("ERROR: working tree not clean, refusing to --apply")

    game_versions = fetch_game_versions()
    attempted, failed = set(), False
    while True:
        manifest = common.load_manifest()
        for t in manifest["targets"]:
            t["game_versions"] = [gv for gv in t["game_versions"] if gv not in args.simulate]
        stable, unstable = pick_candidates(game_versions, manifest)
        todo = [p for p in (stable, unstable) if p and p.raw not in attempted]
        if not attempted:
            print(f"newest uncovered stable:   {stable.raw if stable else '-'}")
            print(f"newest uncovered unstable: {unstable.raw if unstable else '-'}")
        if not todo:
            break
        p = todo[0]
        attempted.add(p.raw)
        action = classify(p, manifest)
        print("plan: " + action.describe())
        if action.kind == "skip":
            continue
        deps = resolve(p.raw)
        if deps is None or not args.apply:
            continue
        failed |= not execute(action, deps, manifest)
    if not attempted:
        print("nothing to do, every Minecraft version newer than 1.21.11 is covered")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
