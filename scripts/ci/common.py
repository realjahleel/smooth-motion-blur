"""Shared helpers for the CI scripts (Python 3 standard library only).

CLI:
  python3 scripts/ci/common.py matrix            # versions.json targets as compact JSON (GitHub matrix)
  python3 scripts/ci/common.py build-test <dir>  # gradle build + headless client gametest
"""
import collections
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "versions.json"
USER_AGENT = "realjahleel/smooth-motion-blur (github actions)"
GAMETEST_MARKER = "MotionBlurGameTest passed"
GAMETEST_TIMEOUT = 15 * 60  # seconds; a hung game must fail with logs instead of eating the job timeout


# ---------------------------------------------------------------- manifest

def load_manifest(path=MANIFEST):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def dump_manifest(manifest):
    """Same layout as the hand-written file: one key per line, lists inline."""
    out = ["{"]
    top = [k for k in manifest if k != "targets"]
    for k in top:
        out.append(f"  {json.dumps(k)}: {json.dumps(manifest[k])},")
    out.append('  "targets": [')
    for i, t in enumerate(manifest["targets"]):
        out.append("    {")
        items = list(t.items())
        for j, (k, v) in enumerate(items):
            comma = "," if j < len(items) - 1 else ""
            out.append(f"      {json.dumps(k)}: {json.dumps(v)}{comma}")
        out.append("    }" + ("," if i < len(manifest["targets"]) - 1 else ""))
    out.append("  ]")
    out.append("}")
    return "\n".join(out) + "\n"


def save_manifest(manifest, path=MANIFEST):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(dump_manifest(manifest))


def find_target(manifest, target_id):
    for t in manifest["targets"]:
        if t["id"] == target_id:
            return t
    raise SystemExit(f"ERROR: target {target_id!r} not in versions.json")


# ---------------------------------------------------------- gradle.properties

def read_properties(path):
    props = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def set_property(text, key, value):
    """Replace key=... in gradle.properties text, keep everything else; append if missing."""
    pattern = re.compile(rf"^(\s*{re.escape(key)}\s*=).*$", re.M)
    if pattern.search(text):
        return pattern.sub(lambda m: m.group(1) + value, text, count=1)
    return text.rstrip("\n") + f"\n{key}={value}\n"


def target_properties(target):
    return read_properties(ROOT / target["dir"] / "gradle.properties")


def find_jar(target_dir, props):
    """versions/<id>/build/libs/smooth-motion-blur-<mc_label>-<mod_version>.jar (sources jar excluded)."""
    libs = Path(target_dir) / "build" / "libs"
    mod_version = props["mod_version"]
    label = props.get("mc_label")
    if label:
        exact = libs / f"smooth-motion-blur-{label}-{mod_version}.jar"
        if exact.is_file():
            return exact
    hits = sorted(p for p in libs.glob(f"smooth-motion-blur-*-{mod_version}.jar")
                  if not p.name.endswith("-sources.jar"))
    return hits[0] if hits else None


# ---------------------------------------------------------------- http

def http(method, url, data=None, headers=None, timeout=60):
    """Returns (status, parsed JSON or text). Raises urllib.error.HTTPError on 4xx/5xx."""
    h = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read().decode("utf-8")
        try:
            return r.status, json.loads(body) if body else None
        except ValueError:
            return r.status, body


def get_json(url, headers=None):
    return http("GET", url, headers=headers)[1]


# ---------------------------------------------------------------- build + gametest

def gradlew():
    # gradlew is stored without the exec bit; "sh gradlew" avoids a chmod that would dirty the tree
    return [str(ROOT / "gradlew.bat")] if os.name == "nt" else ["sh", str(ROOT / "gradlew")]


def run_logged(cmd, env=None, log=None, timeout=None):
    """Runs cmd, streams output, returns (exit code, all output lines).
    With a timeout the whole process tree is killed and the exit code is 124."""
    print("+ " + " ".join(cmd), flush=True)
    lines = []
    kwargs = {"start_new_session": True} if os.name != "nt" else {}
    proc = subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", **kwargs)
    timed_out = threading.Event()

    def kill():
        timed_out.set()
        print(f"TIMEOUT after {timeout}s, killing {cmd[0]}", flush=True)
        try:
            if os.name != "nt":
                os.killpg(proc.pid, signal.SIGKILL)
            else:
                proc.kill()
        except OSError:
            pass

    timer = threading.Timer(timeout, kill) if timeout else None
    if timer:
        timer.start()
    try:
        for line in proc.stdout:
            sys.stdout.write(line)
            lines.append(line.rstrip("\n"))
            if log is not None:
                log.append(line.rstrip("\n"))
        code = proc.wait()
    finally:
        if timer:
            timer.cancel()
    return (124 if timed_out.is_set() else code), lines


def annotate_failure(title, text):
    """Surfaces failure details as GitHub error annotations. Annotations are readable
    through the public API, raw job logs are not (they need admin rights)."""
    if os.environ.get("GITHUB_ACTIONS") != "true" or not text:
        return
    text = text[-3500:]
    enc = text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
    print(f"::error title={title}::{enc}", flush=True)


def tail(path, n):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return "".join(collections.deque(f, maxlen=n))
    except OSError:
        return ""


def report_gametest_failure(target_dir, lines):
    run_dir = ROOT / target_dir / "build" / "clientgametest"
    annotate_failure("gradle output (tail)", "\n".join(lines[-45:]))
    log = tail(run_dir / "logs" / "latest.log", 60)
    errors = "".join(l for l in log.splitlines(keepends=True)
                     if any(k in l for k in ("ERROR", "Exception", "Caused by", "WARN", "FATAL")))
    annotate_failure("latest.log (errors/warnings)", errors or log)
    annotate_failure("latest.log (tail)", log)
    for crash in sorted((run_dir / "crash-reports").glob("*.txt"))[-1:]:
        annotate_failure("crash report", tail(crash, 70))


def build_and_gametest(target_dir, overrides=None, log=None):
    """./gradlew -p <dir> build, then the client gametest headless (xvfb + Mesa llvmpipe on Linux).
    overrides: {property: value} passed as -P (beats gradle.properties). Returns True on success."""
    if log is None:
        log = collections.deque(maxlen=2000)
    props = [f"-P{k}={v}" for k, v in (overrides or {}).items()]
    code, lines = run_logged([*gradlew(), "-p", str(target_dir), "build", "--stacktrace", *props], log=log)
    if code != 0:
        log.append(f"BUILD FAILED (exit {code})")
        annotate_failure("build failed", "\n".join(lines[-60:]))
        return False

    # llvmpipe would otherwise rasterize on every core and starve the integrated server
    env = dict(os.environ, LIBGL_ALWAYS_SOFTWARE="1", LP_NUM_THREADS="2")
    cmd = [*gradlew(), "-p", str(target_dir), "runClientGameTest", "--stacktrace", *props]
    if shutil.which("xvfb-run"):
        cmd = ["xvfb-run", "-a", "-s", "-screen 0 1280x720x24", *cmd]
    code, lines = run_logged(cmd, env=env, log=log, timeout=GAMETEST_TIMEOUT)
    passed = any(GAMETEST_MARKER in l for l in lines)
    if code != 0 or not passed:
        log.append(f"GAMETEST FAILED (exit {code}, marker {'found' if passed else 'missing'}: {GAMETEST_MARKER!r})")
        report_gametest_failure(target_dir, lines)
        return False
    print(f"OK: {GAMETEST_MARKER}", flush=True)
    return True


def main(argv):
    if len(argv) >= 1 and argv[0] == "matrix":
        targets = load_manifest()["targets"]
        print(json.dumps([{"id": t["id"], "dir": t["dir"], "java": t["java"]} for t in targets],
                         separators=(",", ":")))
        return 0
    if len(argv) == 2 and argv[0] == "build-test":
        return 0 if build_and_gametest(argv[1]) else 1
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
