# Smooth Motion Blur

Velocity-based motion blur for Minecraft 1.21.11 and 26.x (Fabric) with clean directional
streaks — sharp HUD, sharp hand, instant response. VulkanMod compatible.

Fast camera flicks produce directional streaks like human vision; the screen
center, first-person hand and GUI stay sharp. Stop moving and the image is
instantly crisp again.

## Usage

| Command | Effect |
|---|---|
| `/motionblur` | Show current status |
| `/motionblur 0` | Disable |
| `/motionblur 1-100` | Enable + set strength |

`[` / `]` fine-tune the strength in 5% steps. Settings are saved to
`config/motionblur.json`.

## Building

Every Minecraft target is its own standalone Gradle build under `versions/<id>/`
(`1.21.11`, `26.1`, `26.2`, `26.3`, `26.4-snapshot`), all sharing the root Gradle
wrapper. `versions.json` lists the targets with their Java version and the Modrinth
game versions each jar covers. Versions live in `versions/<id>/gradle.properties`
(`mod_version`, `minecraft_version`, `fabric_api_version`, `loader_version`, `mc_label`).

```
./gradlew -p versions/26.3 build              # build one target
./gradlew -p versions/26.3 runClientGameTest  # start the game, check the blur, take screenshots
```

The jar ends up in `versions/<id>/build/libs/smooth-motion-blur-<mc_label>-<mod_version>.jar`.
The gametest prints `MotionBlurGameTest passed` on success and saves its screenshots to
`versions/<id>/build/clientgametest/screenshots/`. 1.21.11 needs Java 21, the 26.x
targets need Java 25.

## Releases & automation

- **Every push / PR** builds all targets and runs their gametest headless on GitHub
  Actions (`.github/workflows/build.yml`); jars and screenshots are attached to the run.
- **Tag `vX.Y.Z`** (e.g. `git tag v1.1.0 && git push origin v1.1.0`) builds, gametests
  and publishes every target to Modrinth as `<mod_version>+<id>`, with the matching
  `## <mod_version>` section of `CHANGELOG.md` as changelog. Versions that already exist
  are skipped, so re-running is safe. A dry run is available under Actions, "publish",
  "Run workflow".
- **Daily auto-update** checks Fabric for new Minecraft versions. Patch releases are
  tested against the existing jar, snapshots update `versions/<line>-snapshot`, new
  releases get their own folder. Every port must build and pass the gametest before it
  is committed and published; a failed port opens an issue "Auto-port to <version> failed".
- Needs the repository secret `MODRINTH_TOKEN` (Modrinth PAT with VERSION_CREATE,
  VERSION_WRITE and PROJECT_READ).

The CI scripts live in `scripts/ci/` (Python 3, standard library only):
`python3 scripts/ci/auto_update.py` shows what the auto-update would do,
`python3 scripts/ci/publish_modrinth.py --all --dry-run` shows the Modrinth uploads,
`python3 -m unittest discover -s scripts/ci` runs their tests.

## How it works

Every frame the world-render matrices are captured (`WorldRendererMixin`).
After the world is rendered (`GameRendererMixin`), a fullscreen pass
reconstructs each pixel's world position from the depth buffer, reprojects it
with the previous frame's matrices, and smears along the resulting
screen-space velocity (`MotionBlurRenderer` +
`assets/motionblur/shaders/post/motion_blur.fsh`). The pass is built entirely
on vanilla's GpuDevice/RenderPass abstraction — no raw OpenGL — so backend
replacements like VulkanMod keep working.

## Credits & License

The velocity-reprojection blur technique is adapted from
[Natural Motion Blur](https://github.com/ItsPasi/natural-motionblur-fabric)
by **ItsPasi**.

Licensed under **LGPL-3.0-only** — see [LICENSE](LICENSE).
