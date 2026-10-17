package com.jahleel.motionblur.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;

/**
 * Simple config screen (used by Mod Menu): on/off toggle, strength slider
 * and a toggle for blurring the first-person hand/held items. Turning the
 * blur off keeps the configured strength, so turning it back on restores it.
 */
public final class MotionBlurConfigScreen extends Screen {
	private static final int WIDGET_WIDTH = 220;

	private final @Nullable Screen parent;

	public MotionBlurConfigScreen(@Nullable Screen parent) {
		super(Text.translatable("screen.motionblur.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		MotionBlurConfig config = MotionBlurClient.config;
		int x = (this.width - WIDGET_WIDTH) / 2;
		int y = this.height / 6 + 20;

		ButtonWidget toggle = ButtonWidget.builder(enabledText(config), button -> {
			config.enabled = !config.enabled;
			button.setMessage(enabledText(config));
		}).dimensions(x, y, WIDGET_WIDTH, 20).build();
		this.addDrawableChild(toggle);

		this.addDrawableChild(new StrengthSlider(x, y + 28, WIDGET_WIDTH, 20, config));

		this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, button -> this.close())
				.dimensions(x, y + 72, WIDGET_WIDTH, 20).build());
	}

	private static Text enabledText(MotionBlurConfig config) {
		return Text.translatable(config.enabled
				? "option.motionblur.enabled.on"
				: "option.motionblur.enabled.off");
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 6 - 8, 0xFFFFFFFF);
	}

	@Override
	public void close() {
		MotionBlurClient.config.save();
		if (this.client != null) {
			this.client.setScreen(this.parent);
		}
	}

	private static final class StrengthSlider extends SliderWidget {
		private static final float MIN = 1.0f;
		private static final float MAX = 100.0f;

		private final MotionBlurConfig config;

		StrengthSlider(int x, int y, int width, int height, MotionBlurConfig config) {
			super(x, y, width, height, Text.empty(), toSlider(config.strength * MotionBlurConfig.DISPLAY_SCALE));
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
			setMessage(Text.translatable("option.motionblur.strength", percent()));
		}

		@Override
		protected void applyValue() {
			config.strength = percent() / MotionBlurConfig.DISPLAY_SCALE;
		}
	}
}
