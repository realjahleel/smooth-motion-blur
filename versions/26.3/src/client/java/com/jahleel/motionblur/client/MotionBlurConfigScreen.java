package com.jahleel.motionblur.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Simple config screen (used by Mod Menu): on/off toggle, strength slider
 * and a Done button. Turning the blur off keeps the configured strength,
 * so turning it back on restores it.
 */
public final class MotionBlurConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 220;

	private final @Nullable Screen parent;

	public MotionBlurConfigScreen(@Nullable Screen parent) {
		super(Component.translatable("screen.motionblur.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		MotionBlurConfig config = MotionBlurClient.config;
		int x = (this.width - WIDGET_WIDTH) / 2;
		int y = this.height / 6 + 20;

		Button toggle = Button.builder(enabledText(config), button -> {
			config.enabled = !config.enabled;
			button.setMessage(enabledText(config));
		}).bounds(x, y, WIDGET_WIDTH, 20).build();
		this.addRenderableWidget(toggle);

		this.addRenderableWidget(new StrengthSlider(x, y + 28, WIDGET_WIDTH, 20, config));

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds(x, y + 72, WIDGET_WIDTH, 20).build());
	}

	private static Component enabledText(MotionBlurConfig config) {
		return Component.translatable(config.enabled
				? "option.motionblur.enabled.on"
				: "option.motionblur.enabled.off");
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float deltaTicks) {
		super.extractRenderState(graphics, mouseX, mouseY, deltaTicks);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 6 - 8, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		MotionBlurClient.config.save();
		this.minecraft.gui.setScreen(this.parent);
	}

	private static final class StrengthSlider extends AbstractSliderButton {
		private static final float MIN = 1.0f;
		private static final float MAX = 100.0f;

		private final MotionBlurConfig config;

		StrengthSlider(int x, int y, int width, int height, MotionBlurConfig config) {
			super(x, y, width, height, Component.empty(), toSlider(config.strength * MotionBlurConfig.DISPLAY_SCALE));
			this.config = config;
			updateMessage();
		}

		private static double toSlider(float percent) {
			return (Math.max(MIN, Math.min(MAX, percent)) - MIN) / (MAX - MIN);
		}

		private int percent() {
			return Math.round(MIN + (float) this.value * (MAX - MIN));
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("option.motionblur.strength", percent()));
		}

		@Override
		protected void applyValue() {
			config.strength = percent() / MotionBlurConfig.DISPLAY_SCALE;
		}
	}
}
