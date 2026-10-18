package com.jahleel.motionblur.client.mixin;

import com.jahleel.motionblur.client.MotionBlurRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(
			method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
					shift = At.Shift.AFTER
			)
	)
	private void motionblur$afterWorldRender(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
		MotionBlurRenderer.onWorldRendered(this.minecraft);
	}
}
