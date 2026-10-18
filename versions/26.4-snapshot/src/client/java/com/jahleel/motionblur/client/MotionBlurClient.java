package com.jahleel.motionblur.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MotionBlurClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("motionblur");
	public static MotionBlurConfig config = new MotionBlurConfig();
	/** Diagnostic shader mode (0 = off), set via /motionblur debug <n>. Not persisted. */
	public static int debugMode = 0;

	private static KeyMapping increaseKey;
	private static KeyMapping decreaseKey;
	private static boolean warnAboutExclusiveFullscreen;

	@Override
	public void onInitializeClient() {
		config = MotionBlurConfig.load();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("motionblur", "main"));
		increaseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.motionblur.increase", InputConstants.Type.KEYBOARD, InputConstants.KEY_RBRACKET, category));
		decreaseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.motionblur.decrease", InputConstants.Type.KEYBOARD, InputConstants.KEY_LBRACKET, category));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal("motionblur")
						.executes(context -> {
							if (config.enabled) {
								context.getSource().sendFeedback(Component.translatable(
										"message.motionblur.status", Math.round(config.strength * MotionBlurConfig.DISPLAY_SCALE)));
							} else {
								context.getSource().sendFeedback(Component.translatable("message.motionblur.disabled"));
							}
							return 1;
						})
						.then(ClientCommands.literal("debug")
								.then(ClientCommands.argument("mode", IntegerArgumentType.integer(0, 3))
										.executes(context -> {
											debugMode = IntegerArgumentType.getInteger(context, "mode");
											context.getSource().sendFeedback(Component.literal("Motion Blur debug mode: " + debugMode));
											return 1;
										})))
						.then(ClientCommands.argument("strength", IntegerArgumentType.integer(0, 100))
								.executes(context -> {
									int value = IntegerArgumentType.getInteger(context, "strength");
									applyStrength(value);
									if (config.enabled) {
										context.getSource().sendFeedback(Component.translatable(
												"message.motionblur.strength", Math.round(config.strength * MotionBlurConfig.DISPLAY_SCALE)));
									} else {
										context.getSource().sendFeedback(Component.translatable("message.motionblur.disabled"));
									}
									return 1;
								}))));

		warnAboutExclusiveFullscreen = detectVulkanModExclusiveFullscreen();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (warnAboutExclusiveFullscreen && client.player != null) {
				warnAboutExclusiveFullscreen = false;
				client.player.sendSystemMessage(Component.literal(
						"[Smooth Motion Blur] VulkanMod is set to 'Exclusive Fullscreen', which is known to freeze "
						+ "during loading screens. Switching to 'Windowed Fullscreen' in Video Settings is recommended.")
						.withColor(0xFFAA00));
			}

			boolean changed = false;
			while (increaseKey.consumeClick()) {
				config.strength = Math.min(5.0f, config.strength + 0.25f);
				config.enabled = true;
				changed = true;
				sendStrength(client);
			}
			while (decreaseKey.consumeClick()) {
				config.strength = Math.max(0.05f, config.strength - 0.25f);
				changed = true;
				sendStrength(client);
			}
			if (changed) {
				config.save();
			}
		});

		LOGGER.info("Smooth Motion Blur initialized (strength={}, enabled={})", config.strength, config.enabled);
	}

	/**
	 * VulkanMod's "Exclusive Fullscreen" window mode (windowMode 2) is known to
	 * deadlock during loading screens. We never touch another mod's settings,
	 * but we do warn the player once and point at the fix.
	 */
	private static boolean detectVulkanModExclusiveFullscreen() {
		if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("vulkanmod")) {
			return false;
		}
		try {
			java.nio.file.Path settings = net.fabricmc.loader.api.FabricLoader.getInstance()
					.getConfigDir().resolve("vulkanmod_settings.json");
			if (!java.nio.file.Files.exists(settings)) {
				return false;
			}
			com.google.gson.JsonObject json = com.google.gson.JsonParser
					.parseString(java.nio.file.Files.readString(settings)).getAsJsonObject();
			return json.has("windowMode") && json.get("windowMode").getAsInt() == 2;
		} catch (Exception e) {
			return false;
		}
	}

	/** Applies a 0-100 strength value: 0 disables the blur, anything else enables it. */
	public static void applyStrength(int value) {
		debugMode = 0;
		if (value <= 0) {
			config.enabled = false;
			MotionBlurRenderer.reset();
		} else {
			config.enabled = true;
			config.strength = value / MotionBlurConfig.DISPLAY_SCALE;
		}
		config.save();
	}

	private static void sendStrength(Minecraft client) {
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.translatable("message.motionblur.strength",
					Math.round(config.strength * MotionBlurConfig.DISPLAY_SCALE)));
		}
	}
}
