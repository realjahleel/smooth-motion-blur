package com.jahleel.motionblur.client.test;

import com.jahleel.motionblur.client.MotionBlurClient;
import com.jahleel.motionblur.client.MotionBlurRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Dev-only end-to-end test: creates a world, moves the player so the
 * accumulation blur actually has motion to smear, and verifies the blur
 * pass ran without crashing the renderer.
 */
public final class MotionBlurGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (System.getProperty("motionblur.promoGif") != null) {
			recordPromoFrames(context);
			return;
		}
		MotionBlurClient.config.enabled = true;
		MotionBlurClient.config.strength = 0.7f;

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getClientWorld().waitForChunksRender();
			context.waitTicks(20);

			if (!MotionBlurRenderer.hasHistory()) {
				throw new AssertionError("Motion blur pass never ran: no accumulation history after rendering");
			}

			context.getInput().holdKey(options -> options.forwardKey);
			context.waitTicks(40);
			context.takeScreenshot("motionblur-active");
			context.getInput().releaseKey(options -> options.forwardKey);

			// Turn the camera right before the screenshot frame renders, so the
			// captured frame has real inter-frame motion and must show streaks.
			MotionBlurClient.config.strength = 2.0f;
			context.runOnClient(client -> {
				if (client.player != null) {
					client.player.setYaw(client.player.getYaw() + 30.0f);
				}
			});
			context.takeScreenshot("motionblur-spinning");

			MotionBlurClient.config.enabled = false;
			context.waitTicks(10);
			if (MotionBlurRenderer.hasHistory()) {
				throw new AssertionError("Motion blur history was not reset after disabling");
			}
			context.takeScreenshot("motionblur-disabled");

			MotionBlurClient.config.enabled = true;
			context.waitTicks(10);
			if (!MotionBlurRenderer.hasHistory()) {
				throw new AssertionError("Motion blur did not resume after re-enabling");
			}
		}

		MotionBlurClient.LOGGER.info("MotionBlurGameTest passed: blur pass ran, reset and resume work");
	}

	/** Captures a camera-pan frame sequence in a real world for the promo GIF. */
	private static void recordPromoFrames(ClientGameTestContext context) {
		MotionBlurClient.config.enabled = true;
		MotionBlurClient.config.strength = 2.5f;

		try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false).create()) {
			world.getClientWorld().waitForChunksRender();
			context.waitTicks(60);
			for (int i = 0; i < 36; i++) {
				context.runOnClient(client -> {
					if (client.player != null) {
						client.player.setPitch(-5.0f);
						client.player.setYaw(client.player.getYaw() + 5.0f);
					}
				});
				context.takeScreenshot(String.format("gif-%02d", i));
			}
		}
		MotionBlurClient.LOGGER.info("Promo GIF frames captured");
	}
}
