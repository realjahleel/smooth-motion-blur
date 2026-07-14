package com.jahleel.motionblur.client.mixin;

import com.jahleel.motionblur.client.MotionBlurRenderer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.memory.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void motionblur$captureFrame(
			ObjectAllocator allocator,
			RenderTickCounter tickCounter,
			boolean renderBlockOutline,
			Camera camera,
			Matrix4f positionMatrix,
			Matrix4f basicProjectionMatrix,
			Matrix4f projectionMatrix,
			GpuBufferSlice fogBuffer,
			Vector4f fogColor,
			boolean renderSky,
			CallbackInfo ci
	) {
		MotionBlurRenderer.captureFrame(positionMatrix, basicProjectionMatrix, camera.getCameraPos());
	}
}
