package com.jahleel.motionblur.client;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Optional;

/**
 * Velocity-based reprojection motion blur (the "Natural Motion Blur" look).
 *
 * <p>Each frame the world-render matrices are captured. The blur pass
 * reconstructs every pixel's world position from the depth buffer,
 * reprojects it with the previous frame's matrices and smears along the
 * resulting screen-space velocity. Fast camera turns produce directional
 * streaks while the screen centre and the first-person hand stay sharp.
 *
 * <p>Blur technique adapted from Natural Motion Blur by ItsPasi
 * (LGPL-3.0-only, https://github.com/ItsPasi/natural-motionblur-fabric).
 * Implemented exclusively on vanilla's GpuDevice/RenderPass abstraction
 * (no raw OpenGL), so backend replacements keep working.
 */
public final class MotionBlurRenderer {
	private static final int MAX_SAMPLES = 100;
	private static final int UBO_SIZE = new Std140SizeCalculator()
			.putMat4f().putMat4f().putMat4f().putMat4f()
			.putVec3().putVec2().putFloat().putInt().putInt().putInt().putInt()
			.get();

	public static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("motionblur", "pipeline/motion_blur"))
			.withVertexShader("core/screentriangle")
			.withFragmentShader(Identifier.fromNamespaceAndPath("motionblur", "post/motion_blur"))
			.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("MainSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("MainDepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
					.withUniform("MotionBlurUniforms", UniformType.UNIFORM_BUFFER)
					.build())
			// No blending, no depth test/write: a plain fullscreen overwrite.
			.withColorTargetState(ColorTargetState.DEFAULT)
			.withDepthStencilState(Optional.empty())
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();

	// Matrices captured at the start of the current world render.
	private static final Matrix4f modelView = new Matrix4f();
	private static final Matrix4f projection = new Matrix4f();
	private static final Matrix4f prevModelView = new Matrix4f();
	private static final Matrix4f prevProjection = new Matrix4f();
	private static final Matrix4f mvInverse = new Matrix4f();
	private static final Matrix4f projInverse = new Matrix4f();
	/** Maps a 0..1 depth value to -1..1 NDC z: z' = 2z - 1. */
	private static final Matrix4f DEPTH_TO_NDC = new Matrix4f().translation(0.0f, 0.0f, -1.0f).scale(1.0f, 1.0f, 2.0f);
	private static double camX, camY, camZ;
	private static double prevCamX, prevCamY, prevCamZ;
	private static boolean frameCaptured;
	private static boolean prevFrameReady;
	private static boolean blurRan;

	private static GpuTexture blurTarget;
	private static GpuTextureView blurTargetView;
	private static MappableRingBuffer uniformBuffer;
	private static boolean loggedFirstPass;

	private MotionBlurRenderer() {
	}

	/** Forget the previous-frame state; the next frame re-seeds it. */
	public static void reset() {
		prevFrameReady = false;
		frameCaptured = false;
		blurRan = false;
	}

	/** Whether the blur pass ran for the most recent frame. */
	public static boolean hasHistory() {
		return blurRan;
	}

	/** Called from the GameRenderer mixin right before the level is rendered. */
	public static void captureFrame(Matrix4fc currentModelView, Matrix4fc currentProjection, Vec3 cameraPos) {
		if (frameCaptured) {
			// A second world render in the same frame (e.g. panorama) — keep the first capture.
			return;
		}
		modelView.set(currentModelView);
		projection.set(currentProjection);
		camX = cameraPos.x;
		camY = cameraPos.y;
		camZ = cameraPos.z;
		frameCaptured = true;
	}

	/** Called from the GameRenderer mixin right after the world was rendered. */
	public static void onWorldRendered(Minecraft client) {
		MotionBlurConfig config = MotionBlurClient.config;
		boolean wantBlur = config.enabled
				&& client.level != null
				&& (!config.pauseInGuis || client.gui.screen() == null);

		if (!frameCaptured) {
			blurRan = false;
			return;
		}
		frameCaptured = false;

		if (wantBlur && prevFrameReady) {
			renderBlur(client, config);
			blurRan = true;
		} else {
			blurRan = false;
		}

		prevModelView.set(modelView);
		prevProjection.set(projection);
		prevCamX = camX;
		prevCamY = camY;
		prevCamZ = camZ;
		prevFrameReady = true;
	}

	private static void renderBlur(Minecraft client, MotionBlurConfig config) {
		RenderTarget main = client.gameRenderer.mainRenderTarget();
		GpuTexture color = main.getColorTexture();
		GpuTextureView colorView = main.getColorTextureView();
		GpuTextureView depthView = main.getDepthTextureView();
		if (color == null || colorView == null || depthView == null) {
			return;
		}

		int width = color.getWidth(0);
		int height = color.getHeight(0);
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();

		if (blurTarget == null || blurTarget.getWidth(0) != width || blurTarget.getHeight(0) != height) {
			closeTarget();
			blurTarget = device.createTexture(() -> "Motion blur target",
					GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
					GpuFormat.RGBA8_UNORM, width, height, 1, 1);
			blurTargetView = device.createTextureView(blurTarget);
		}
		if (uniformBuffer == null) {
			uniformBuffer = new MappableRingBuffer(() -> "Motion blur uniforms",
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
		}

		mvInverse.set(modelView).invert();
		projInverse.set(projection).invert();
		if (!device.getDeviceInfo().isZZeroToOne()) {
			// The shader feeds the raw depth value in as NDC z (zero-to-one
			// clip range). On a -1..1 device remap depth -> NDC first.
			projInverse.mul(DEPTH_TO_NDC);
		}

		// Third person or riding: the camera orbits/follows the player, so the
		// camera-projected velocity would smear the whole screen including the
		// centre. Use the true per-pixel depth velocity there instead.
		boolean firstPersonOnFoot = client.options.getCameraType().isFirstPerson()
				&& (client.player == null || !client.player.isPassenger());

		GpuBuffer ubo = uniformBuffer.currentBuffer();
		try (GpuBufferSlice.MappedView mapped = ubo.map(false, true)) {
			Std140Builder.intoBuffer(mapped.data())
					.putMat4f(mvInverse)
					.putMat4f(projInverse)
					.putMat4f(prevModelView)
					.putMat4f(prevProjection)
					.putVec3((float) (camX - prevCamX), (float) (camY - prevCamY), (float) (camZ - prevCamZ))
					.putVec2(width, height)
					.putFloat(config.strength)
					.putInt(MAX_SAMPLES)
					.putInt(firstPersonOnFoot ? 0 : 1)
					.putInt(MotionBlurClient.debugMode)
					.putInt(depthSamplingWorks() ? 1 : 0);
		}

		if (!loggedFirstPass) {
			loggedFirstPass = true;
			MotionBlurClient.LOGGER.info("Motion blur pass running: {}x{}, strength={}, backend={}",
					width, height, config.strength, device.getDeviceInfo().backendName());
		}

		try (RenderPass pass = encoder.createRenderPass(() -> "Motion blur",
				blurTargetView, Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("MotionBlurUniforms", ubo);
			pass.setUniform("MainSampler", colorView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.setUniform("MainDepthSampler", depthView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}

		// Blit the result back onto the main framebuffer via a fullscreen pass.
		// (Not copyTextureToTexture: VulkanMod has that as an unimplemented
		// no-op, which made the blur invisible on its Vulkan backend.)
		try (RenderPass pass = encoder.createRenderPass(() -> "Motion blur blit",
				colorView, Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("InSampler", blurTargetView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(3, 1, 0, 0);
		}
		uniformBuffer.rotate();
	}

	/**
	 * VulkanMod's backend cannot sample the main depth attachment (reads all
	 * zeros), so with VulkanMod the shader falls back to pure camera-motion
	 * blur. Keyed on the mod, not the backend name, so a vanilla Vulkan
	 * backend keeps the depth-aware path.
	 */
	private static boolean depthSamplingWorks() {
		return !VULKANMOD_LOADED;
	}

	private static final boolean VULKANMOD_LOADED =
			net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("vulkanmod");

	private static void closeTarget() {
		if (blurTargetView != null) {
			blurTargetView.close();
			blurTargetView = null;
		}
		if (blurTarget != null) {
			blurTarget.close();
			blurTarget = null;
		}
	}
}
