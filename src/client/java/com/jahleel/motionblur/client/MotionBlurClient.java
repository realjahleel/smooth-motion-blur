package com.jahleel.motionblur.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MotionBlurClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("motionblur");
	public static MotionBlurConfig config = new MotionBlurConfig();

	private static KeyBinding increaseKey;
	private static KeyBinding decreaseKey;

	@Override
	public void onInitializeClient() {
		config = MotionBlurConfig.load();

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("motionblur", "main"));
		increaseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.motionblur.increase", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, category));
		decreaseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.motionblur.decrease", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, category));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("motionblur")
						.executes(context -> {
							if (config.enabled) {
								context.getSource().sendFeedback(Text.translatable(
										"message.motionblur.status", Math.round(config.strength * 100)));
							} else {
								context.getSource().sendFeedback(Text.translatable("message.motionblur.disabled"));
							}
							return 1;
						})
						.then(ClientCommandManager.argument("strength", IntegerArgumentType.integer(0, 300))
								.executes(context -> {
									int value = IntegerArgumentType.getInteger(context, "strength");
									applyStrength(value);
									if (config.enabled) {
										context.getSource().sendFeedback(Text.translatable(
												"message.motionblur.strength", Math.round(config.strength * 100)));
									} else {
										context.getSource().sendFeedback(Text.translatable("message.motionblur.disabled"));
									}
									return 1;
								}))));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean changed = false;
			while (increaseKey.wasPressed()) {
				config.strength = Math.min(3.0f, config.strength + 0.05f);
				config.enabled = true;
				changed = true;
				sendStrength(client);
			}
			while (decreaseKey.wasPressed()) {
				config.strength = Math.max(0.05f, config.strength - 0.05f);
				changed = true;
				sendStrength(client);
			}
			if (changed) {
				config.save();
			}
		});

		LOGGER.info("Smooth Motion Blur initialized (strength={}, enabled={})", config.strength, config.enabled);
	}

	/** Applies a 0-100 strength value: 0 disables the blur, anything else enables it. */
	public static void applyStrength(int value) {
		if (value <= 0) {
			config.enabled = false;
			MotionBlurRenderer.reset();
		} else {
			config.enabled = true;
			config.strength = value / 100.0f;
		}
		config.save();
	}

	private static void sendStrength(MinecraftClient client) {
		if (client.player != null) {
			client.player.sendMessage(Text.translatable("message.motionblur.strength",
					Math.round(config.strength * 100)), true);
		}
	}
}
