package com.jahleel.motionblur.client.mixin;

import com.jahleel.motionblur.client.MotionBlurRenderer;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
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

	/**
	 * Start of the world render: capture the view rotation, the projection
	 * actually used for the level (including view bobbing / nausea, which
	 * only exists as a local in renderLevel) and the camera position.
	 */
	@Inject(
			method = "renderLevel()V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"
			)
	)
	private void motionblur$captureFrame(CallbackInfo ci,
			@Local CameraRenderState cameraState,
			@Local Matrix4f projectionMatrix) {
		MotionBlurRenderer.captureFrame(cameraState.viewRotationMatrix, projectionMatrix, cameraState.pos);
	}

	@Inject(
			method = "render()V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V",
					shift = At.Shift.AFTER
			)
	)
	private void motionblur$afterWorldRender(CallbackInfo ci) {
		MotionBlurRenderer.onWorldRendered(this.minecraft);
	}
}
