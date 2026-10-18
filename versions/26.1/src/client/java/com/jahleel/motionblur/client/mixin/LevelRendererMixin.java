package com.jahleel.motionblur.client.mixin;

import com.jahleel.motionblur.client.MotionBlurRenderer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void motionblur$captureFrame(
			GraphicsResourceAllocator allocator,
			DeltaTracker deltaTracker,
			boolean renderBlockOutline,
			CameraRenderState cameraState,
			Matrix4fc modelViewMatrix,
			GpuBufferSlice terrainFog,
			Vector4f fogColor,
			boolean renderSky,
			ChunkSectionsToRender chunkSectionsToRender,
			CallbackInfo ci
	) {
		MotionBlurRenderer.captureFrame(modelViewMatrix, cameraState.projectionMatrix, cameraState.pos);
	}
}
