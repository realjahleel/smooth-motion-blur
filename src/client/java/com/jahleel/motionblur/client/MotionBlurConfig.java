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
	 * Blur strength in [0.05, 3.0] (set via /motionblur 0-300). Scales the
	 * length of the velocity streaks; above 1.0 the streaks extend further
	 * than the actual camera motion.
	 */
	public float strength = 0.8f;
	/** Skip blur while a GUI screen (inventory, menus) is open. */
	public boolean pauseInGuis = true;
	/** Also blur the first-person hand and held items (main/off hand). */
	public boolean blurHand = false;

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
		if (Float.isNaN(strength)) strength = 0.8f;
		strength = Math.max(0.05f, Math.min(3.0f, strength));
	}
}
