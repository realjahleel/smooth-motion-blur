# Changelog

Each `## <version>` section is used as the Modrinth changelog for that version.

## 1.1.0

- Multi-version support: separate builds for Minecraft 1.21.11, 26.1-26.1.2, 26.2, 26.3
  and 26.4-snapshot-4 (beta).
- VulkanMod depth fallback is now keyed on the VulkanMod mod being loaded instead of the
  backend name, so it keeps working when the backend reports a different name.
- Automated builds: every version is built and gametested headless on GitHub Actions,
  releases are published to Modrinth automatically.

## 1.0.0

- Initial release: velocity-based motion blur with directional streaks, sharp HUD and
  hand, `/motionblur 0-100`, `[` / `]` fine-tuning, Mod Menu config screen, VulkanMod
  compatible.
