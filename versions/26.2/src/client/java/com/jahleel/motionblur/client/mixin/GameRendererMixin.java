package com.jahleel.motionblur.client.mixin;

import com.jahleel.motionblur.client.MotionBlurRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	/**
	 * In 26.2 renderLevel copies cameraState.projectionMatrix and applies hurt
	 * tilt, view bobbing and the nausea/portal warp to that copy only, then
	 * uploads it through ProjectionMatrixBuffer.getBuffer(Matrix4f). This is the
	 * real world projection (what 1.21.11 received as WorldRenderer.render's
	 * projection argument), so capture it here.
	 */
	@ModifyArg(
			method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
			)
	)
	private Matrix4f motionblur$captureWorldProjection(Matrix4f projection) {
		MotionBlurRenderer.setWorldProjection(projection);
		return projection;
	}

	@Inject(
			method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
					shift = At.Shift.AFTER
			)
	)
	private void motionblur$afterWorldRender(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		MotionBlurRenderer.onWorldRendered(this.minecraft);
	}
}
