package com.jahleel.motionblur.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simple JSON config stored at config/motionblur.json.
 */
public final class MotionBlurConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("motionblur");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("motionblur.json");

	/** Master toggle. */
	public boolean enabled = true;
	/**
	 * Scale between the user-facing 0-100 strength and the internal blend
	 * factor: 100 on the slider/command equals an internal factor of 5.0.
	 */
	public static final float DISPLAY_SCALE = 20.0f;

	/**
	 * Internal blend factor (streak length multiplier) in [0.05, 5.0].
	 * Shown to the user as 0-100 (value * 20). Higher = longer trails.
	 */
	public float strength = 1.0f;
	/** Skip blur while a GUI screen (inventory, menus) is open. */
	public boolean pauseInGuis = true;

	public static MotionBlurConfig load() {
		try {
			if (Files.exists(PATH)) {
				MotionBlurConfig cfg = GSON.fromJson(Files.readString(PATH), MotionBlurConfig.class);
				if (cfg != null) {
					cfg.clamp();
					return cfg;
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Could not read motionblur.json, using defaults", e);
		}
		MotionBlurConfig cfg = new MotionBlurConfig();
		cfg.save();
		return cfg;
	}

	public void save() {
		try {
			clamp();
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this));
		} catch (IOException e) {
			LOGGER.warn("Could not save motionblur.json", e);
		}
	}

	public void clamp() {
		if (Float.isNaN(strength)) strength = 1.0f;
		strength = Math.max(0.05f, Math.min(5.0f, strength));
	}
}
