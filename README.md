# Smooth Motion Blur

Velocity-based motion blur for Minecraft 1.21.11 (Fabric) with clean directional
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

```
./gradlew build
```

The jar ends up in `build/libs/`. Requires Java 21.

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
