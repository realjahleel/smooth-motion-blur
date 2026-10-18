"""Offline tests for the CI decision logic: python -m unittest discover -s scripts/ci"""
import copy
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import auto_update as au  # noqa: E402
import common  # noqa: E402
import publish_modrinth as pm  # noqa: E402


def meta(*entries):
    """Fabric meta style list, newest first. 'x' = stable, 'x?' = unstable."""
    return [{"version": e.rstrip("?"), "stable": not e.endswith("?")} for e in entries]


# real head of https://meta.fabricmc.net/v2/versions/game on 2026-10-17 (shortened)
TODAY = meta("26.4-snapshot-4?", "26.4-snapshot-3?", "26.4-snapshot-2?", "26.4-snapshot-1?", "26.3",
             "26.3-rc-3?", "26.3-pre-1?", "26.3-snapshot-10?", "26.2", "26.2-rc-1?", "26.1.2", "26.1.1",
             "26.1", "26.1-snapshot-1?", "1.21.11", "1.21.11-rc-3?", "1.21.10", "1.21.9")

MANIFEST = {
    "modrinth_project": "M5szsQT4",
    "targets": [
        {"id": "1.21.11", "dir": "versions/1.21.11", "java": 21, "game_versions": ["1.21.11"],
         "version_type": "release", "vulkanmod": "0.6.8+1.21.11"},
        {"id": "26.1", "dir": "versions/26.1", "java": 25, "game_versions": ["26.1", "26.1.1", "26.1.2"],
         "version_type": "release", "vulkanmod": "0.6.8+26.1.2"},
        {"id": "26.2", "dir": "versions/26.2", "java": 25, "game_versions": ["26.2"],
         "version_type": "release", "vulkanmod": None},
        {"id": "26.3", "dir": "versions/26.3", "java": 25, "game_versions": ["26.3"],
         "version_type": "release", "vulkanmod": None},
        {"id": "26.4-snapshot", "dir": "versions/26.4-snapshot", "java": 25,
         "game_versions": ["26.4-snapshot-4"], "version_type": "beta", "vulkanmod": None},
    ],
}


def plan(game_versions, manifest=MANIFEST):
    stable, unstable = au.pick_candidates(game_versions, manifest)
    return [au.classify(p, manifest) for p in (stable, unstable) if p]


class ParseTest(unittest.TestCase):
    def test_parse(self):
        p = au.parse_version("26.4-snapshot-5")
        self.assertEqual((p.line, p.stable, p.stage, p.n), ("26.4", False, "snapshot", 5))
        p = au.parse_version("26.3.1")
        self.assertEqual((p.line, p.patch, p.stable), ("26.3", 1, True))
        self.assertIsNone(au.parse_version("25w45a"))
        self.assertIsNone(au.parse_version("26.1 April Fools"))

    def test_ordering(self):
        order = ["26.4-snapshot-9", "26.4-snapshot-10", "26.4-pre-1", "26.4-rc-2", "26.4", "26.4.1", "26.5-snapshot-1"]
        self.assertEqual(sorted(order, key=lambda v: au.parse_version(v).key), order)

    def test_loader_semver(self):
        self.assertEqual(au.loader_semver("26.4-snapshot-4"), "26.4-alpha.4")
        self.assertEqual(au.loader_semver("26.4-pre-1"), "26.4-pre.1")
        self.assertEqual(au.loader_semver("26.4-rc-2"), "26.4-rc.2")
        self.assertEqual(au.loader_semver("26.4.1"), "26.4.1")

    def test_higher_loader(self):
        self.assertEqual(au.higher_loader("0.20.0", "0.19.5"), "0.20.0")
        self.assertEqual(au.higher_loader("0.19.3", "0.19.5"), "0.19.5")

    def test_bump(self):
        self.assertEqual(au.bump_patch("1.1.0"), "1.1.1")
        self.assertEqual(au.bump_patch("1.1.9"), "1.1.10")


class PlanTest(unittest.TestCase):
    def test_today_nothing_to_do(self):
        self.assertEqual(au.pick_candidates(TODAY, MANIFEST), (None, None))

    def test_ignores_older_than_floor(self):
        # 1.21.10 and 1.21.9 are uncovered but older than 1.21.11
        self.assertEqual(plan(TODAY), [])

    def test_new_snapshot_updates_existing_target(self):
        a, = plan(meta("26.4-snapshot-5?") + TODAY)
        self.assertEqual((a.kind, a.version, a.target_id, a.new_target), ("snapshot", "26.4-snapshot-5", "26.4-snapshot", False))

    def test_pre_release_also_updates_snapshot_target(self):
        a, = plan(meta("26.4-pre-1?", "26.4-snapshot-5?") + TODAY)
        self.assertEqual((a.version, a.target_id), ("26.4-pre-1", "26.4-snapshot"))

    def test_stale_older_snapshot_is_not_picked(self):
        # snapshot-3 is uncovered but older than the covered snapshot-4
        stable, unstable = au.pick_candidates(TODAY, MANIFEST)
        self.assertIsNone(unstable)

    def test_new_line_snapshot_creates_folder_from_newest_target(self):
        m = copy.deepcopy(MANIFEST)
        m["targets"].pop()  # no 26.4-snapshot target
        a, = plan(meta("26.5-snapshot-1?") + TODAY, m)
        self.assertEqual((a.kind, a.target_id, a.new_target, a.source_id), ("snapshot", "26.5-snapshot", True, "26.3"))
        a, = plan(meta("26.5-snapshot-1?") + TODAY)
        self.assertEqual(a.source_id, "26.4-snapshot")

    def test_stable_minor_replaces_snapshot_target(self):
        a, = plan(meta("26.4", "26.4-rc-1?", "26.4-pre-1?") + TODAY)
        self.assertEqual((a.kind, a.target_id, a.source_id, a.remove_id, a.new_target),
                         ("minor", "26.4", "26.4-snapshot", "26.4-snapshot", True))

    def test_stable_minor_without_snapshot_copies_newest_release(self):
        m = copy.deepcopy(MANIFEST)
        m["targets"].pop()
        a, = plan(meta("26.4") + TODAY, m)
        self.assertEqual((a.kind, a.source_id, a.remove_id), ("minor", "26.3", None))

    def test_stable_patch(self):
        a, = plan(meta("26.3.1") + TODAY)
        self.assertEqual((a.kind, a.target_id), ("patch", "26.3"))
        a, = plan(meta("26.1.3") + TODAY)
        self.assertEqual((a.kind, a.target_id), ("patch", "26.1"))

    def test_stable_and_unstable_same_day(self):
        acts = plan(meta("26.5-snapshot-1?", "26.4", "26.4-rc-1?") + TODAY)
        self.assertEqual([(a.kind, a.version) for a in acts], [("minor", "26.4"), ("snapshot", "26.5-snapshot-1")])

    def test_unstable_of_released_line_is_ignored(self):
        # a late 26.3 rc is never ported: 26.3 is already released
        self.assertEqual(plan(meta("26.3-rc-4?") + TODAY), [])


class RewriteTest(unittest.TestCase):
    def test_label(self):
        self.assertEqual(au.new_label("26.4-snapshot-4", "26.4-snapshot-4", "26.4-snapshot-5", "26.4-snapshot", "26.4-snapshot"), "26.4-snapshot-5")
        self.assertEqual(au.new_label("26.4-snapshot", "26.4-snapshot-4", "26.4", "26.4-snapshot", "26.4"), "26.4")
        self.assertEqual(au.new_label("26.3", "26.3", "26.4", "26.3", "26.4"), "26.4")

    def test_dependency(self):
        self.assertEqual(au.rewrite_mc_dependency("~26.4-alpha.4", "26.4-snapshot-4", "26.4-snapshot-5"), "~26.4-alpha.5")
        self.assertEqual(au.rewrite_mc_dependency(">=26.4-alpha.4", "26.4-snapshot-4", "26.4-pre-1"), ">=26.4-pre.1")
        self.assertEqual(au.rewrite_mc_dependency("26.4-snapshot-4", "26.4-snapshot-4", "26.4-snapshot-5"), "26.4-snapshot-5")
        self.assertEqual(au.rewrite_mc_dependency(">=26.4-alpha.0", "26.4-snapshot-4", "26.4-snapshot-5"), ">=26.4-alpha.0")
        self.assertEqual(au.rewrite_mc_dependency("~26.4-alpha.4", "26.4-snapshot-4", "26.4", release=True), "~26.4")

    def test_manifest_snapshot_update(self):
        m = copy.deepcopy(MANIFEST)
        a = au.Action("snapshot", "26.4-snapshot-5", target_id="26.4-snapshot")
        au.apply_to_manifest(m, a, "26.4-snapshot-5")
        self.assertEqual(m["targets"][-1]["game_versions"], ["26.4-snapshot-5"])

    def test_manifest_minor_replaces_snapshot(self):
        m = copy.deepcopy(MANIFEST)
        a, = plan(meta("26.4") + TODAY, m)
        au.apply_to_manifest(m, a, "26.4", source=m["targets"][-1])
        self.assertEqual([t["id"] for t in m["targets"]], ["1.21.11", "26.1", "26.2", "26.3", "26.4"])
        self.assertEqual(m["targets"][-1], {"id": "26.4", "dir": "versions/26.4", "java": 25, "game_versions": ["26.4"],
                                             "version_type": "release", "vulkanmod": None})

    def test_manifest_release_inserted_before_snapshots(self):
        m = copy.deepcopy(MANIFEST)
        m["targets"].append({"id": "26.5-snapshot", "dir": "versions/26.5-snapshot", "java": 25,
                             "game_versions": ["26.5-snapshot-1"], "version_type": "beta", "vulkanmod": None})
        a = au.Action("minor", "26.4", target_id="26.4", new_target=True, source_id="26.4-snapshot", remove_id="26.4-snapshot")
        au.apply_to_manifest(m, a, "26.4", source=m["targets"][4])
        self.assertEqual([t["id"] for t in m["targets"]], ["1.21.11", "26.1", "26.2", "26.3", "26.4", "26.5-snapshot"])

    def test_manifest_patch(self):
        m = copy.deepcopy(MANIFEST)
        au.apply_to_manifest(m, au.Action("patch", "26.3.1", target_id="26.3"), "26.3.1")
        self.assertEqual(m["targets"][3]["game_versions"], ["26.3", "26.3.1"])
        self.assertEqual(m["targets"][3]["version_type"], "release")


class PrepareFolderTest(unittest.TestCase):
    """prepare_folder on a throwaway copy of a fake repo, never on the real versions/."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        snap = self.root / "versions" / "26.4-snapshot"
        (snap / "src" / "main" / "resources").mkdir(parents=True)
        (snap / "build" / "libs").mkdir(parents=True)
        (snap / "gradle.properties").write_text(
            "# Fabric\nminecraft_version=26.4-snapshot-4\nmc_label=26.4-snapshot\nloader_version=0.20.0\n"
            "mod_version=1.1.0\nfabric_api_version=0.163.0+26.4\n", encoding="utf-8")
        (snap / "src" / "main" / "resources" / "fabric.mod.json").write_text(
            '{\n\t"depends": {\n\t\t"minecraft": "~26.4-alpha.4",\n\t\t"java": ">=25"\n\t}\n}\n', encoding="utf-8")
        self.old_root, common.ROOT = common.ROOT, self.root
        self.deps = {"fabric_api": "0.170.0+26.4", "loader": "0.19.5"}

    def tearDown(self):
        common.ROOT = self.old_root
        self.tmp.cleanup()

    def test_snapshot_in_place(self):
        a = au.Action("snapshot", "26.4-snapshot-5", target_id="26.4-snapshot")
        d = au.prepare_folder(a, self.deps, MANIFEST)
        props = common.read_properties(d / "gradle.properties")
        self.assertEqual(props, {"minecraft_version": "26.4-snapshot-5", "mc_label": "26.4-snapshot",
                                 "loader_version": "0.20.0", "mod_version": "1.1.1",
                                 "fabric_api_version": "0.170.0+26.4"})
        self.assertIn('"minecraft": "~26.4-alpha.5"', (d / "src/main/resources/fabric.mod.json").read_text())

    def test_minor_copies_snapshot_folder(self):
        a = au.Action("minor", "26.4", target_id="26.4", new_target=True, source_id="26.4-snapshot",
                      remove_id="26.4-snapshot")
        d = au.prepare_folder(a, self.deps, MANIFEST)
        self.assertEqual(d, self.root / "versions" / "26.4")
        self.assertFalse((d / "build").exists())
        props = common.read_properties(d / "gradle.properties")
        self.assertEqual((props["minecraft_version"], props["mc_label"], props["mod_version"]), ("26.4", "26.4", "1.1.1"))
        fmj = (d / "src/main/resources/fabric.mod.json").read_text()
        self.assertIn('"minecraft": "~26.4",', fmj)
        self.assertIn('"java": ">=25"', fmj)
        # source stays untouched until the port succeeded
        self.assertIn("minecraft_version=26.4-snapshot-4",
                      (self.root / "versions/26.4-snapshot/gradle.properties").read_text())


class CommonTest(unittest.TestCase):
    def test_manifest_layout_roundtrip(self):
        text = common.MANIFEST.read_text(encoding="utf-8")
        self.assertEqual(common.dump_manifest(common.load_manifest()).strip(), text.replace("\r\n", "\n").strip())

    def test_set_property(self):
        text = "# c\nminecraft_version=26.3\nmod_version=1.1.0\n"
        self.assertEqual(common.set_property(text, "mod_version", "1.1.1"), "# c\nminecraft_version=26.3\nmod_version=1.1.1\n")
        self.assertEqual(common.set_property(text, "mc_label", "26.4"), text + "mc_label=26.4\n")


class PublishTest(unittest.TestCase):
    CHANGELOG = "# Changelog\n\n## 1.1.0\n\n- multi version\n- builds\n\n## 1.0.0\n\n- initial\n"

    def test_changelog_section(self):
        self.assertEqual(pm.changelog_section("1.1.0", self.CHANGELOG), "- multi version\n- builds")
        self.assertEqual(pm.changelog_section("1.0.0", self.CHANGELOG), "- initial")
        self.assertIsNone(pm.changelog_section("1.1.1", self.CHANGELOG))

    def test_real_changelog_has_current_version(self):
        self.assertIsNotNone(pm.changelog_section("1.1.0"))
        self.assertIsNotNone(pm.changelog_section("1.0.0"))

    def test_payload(self):
        p = pm.version_payload(MANIFEST["targets"][4], "1.1.0", "x")
        self.assertEqual(p["version_number"], "1.1.0+26.4-snapshot")
        self.assertEqual(p["name"], "Smooth Motion Blur 1.1.0 for 26.4-snapshot")
        self.assertEqual(p["game_versions"], ["26.4-snapshot-4"])
        self.assertEqual(p["version_type"], "beta")
        self.assertEqual(p["loaders"], ["fabric"])
        self.assertEqual(p["dependencies"], [{"project_id": "P7dR8mSH", "dependency_type": "required"}])
        self.assertIs(p["featured"], False)
        self.assertEqual(p["project_id"], "M5szsQT4")


if __name__ == "__main__":
    unittest.main()
