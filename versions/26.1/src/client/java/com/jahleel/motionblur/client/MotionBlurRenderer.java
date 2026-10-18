package com.jahleel.motionblur.client;

import com.jahleel.motionblur.client.mixin.RenderPipelinesInvoker;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Optional;
import java.util.OptionalInt;

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
 * (no raw OpenGL), so backend replacements such as VulkanMod keep working.
 */
public final class MotionBlurRenderer {
	private static final int MAX_SAMPLES = 100;
	private static final int UBO_SIZE = new Std140SizeCalculator()
			.putMat4f().putMat4f().putMat4f().putMat4f()
			.putVec3().putVec2().putFloat().putInt().putInt().putInt().putInt()
			.get();

	// RenderPipelines.register is private since 26.1; the invoker keeps the
	// pipeline in the static list so it is precompiled on resource reload,
	// exactly like the 1.21.11 build did.
	public static final RenderPipeline PIPELINE = RenderPipelinesInvoker.motionblur$register(
			RenderPipeline.builder()
					.withLocation(Identifier.fromNamespaceAndPath("motionblur", "pipeline/motion_blur"))
					.withVertexShader("core/screenquad")
					.withFragmentShader(Identifier.fromNamespaceAndPath("motionblur", "post/motion_blur"))
					.withSampler("MainSampler")
					.withSampler("MainDepthSampler")
					.withUniform("MotionBlurUniforms", UniformType.UNIFORM_BUFFER)
					// no blending, full colour write mask
					.withColorTargetState(ColorTargetState.DEFAULT)
					// no depth test, no depth write (the target has no depth attachment)
					.withDepthStencilState(Optional.empty())
					.withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
					.build());

	// Matrices captured at the start of the current world render.
	private static final Matrix4f modelView = new Matrix4f();
	private static final Matrix4f projection = new Matrix4f();
	private static final Matrix4f prevModelView = new Matrix4f();
	private static final Matrix4f prevProjection = new Matrix4f();
	private static final Matrix4f mvInverse = new Matrix4f();
	private static final Matrix4f projInverse = new Matrix4f();
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

	/** Called from the LevelRenderer mixin at the start of every world render. */
	public static void captureFrame(Matrix4fc currentModelView, Matrix4fc currentProjection, Vec3 cameraPos) {
		if (frameCaptured) {
			// A second world render in the same frame (e.g. panorama): keep the first capture.
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
				&& (!config.pauseInGuis || client.screen == null);

		if (!frameCaptured) {
			blurRan = false;
			return;
		}
		frameCaptured = false;

		if (wantBlur && prevFrameReady) {
			// Only counts as ran when the pass was actually recorded.
			blurRan = renderBlur(client, config);
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

	private static boolean renderBlur(Minecraft client, MotionBlurConfig config) {
		RenderTarget main = client.getMainRenderTarget();
		GpuTexture color = main.getColorTexture();
		GpuTextureView colorView = main.getColorTextureView();
		GpuDevice device = RenderSystem.getDevice();
		GpuTextureView depthView = main.getDepthTextureView();
		if (depthView == null) {
			// VulkanMod 0.6.8 (26.1) keeps the main depth texture but never
			// creates its view; make our own so the pass can bind it.
			depthView = fallbackDepthView(device, main.getDepthTexture());
		}
		if (color == null || colorView == null || depthView == null) {
			return false;
		}

		int width = main.width;
		int height = main.height;
		CommandEncoder encoder = device.createCommandEncoder();

		if (blurTarget == null || blurTarget.getWidth(0) != width || blurTarget.getHeight(0) != height) {
			closeTarget();
			blurTarget = device.createTexture(() -> "Motion blur target",
					GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
					TextureFormat.RGBA8, width, height, 1, 1);
			blurTargetView = device.createTextureView(blurTarget);
		}
		if (uniformBuffer == null) {
			uniformBuffer = new MappableRingBuffer(() -> "Motion blur uniforms",
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
		}

		mvInverse.set(modelView).invert();
		projInverse.set(projection).invert();

		// Third person or riding: the camera orbits/follows the player, so the
		// camera-projected velocity would smear the whole screen including the
		// centre. Use the true per-pixel depth velocity there instead.
		boolean firstPersonOnFoot = client.options.getCameraType().isFirstPerson()
				&& (client.player == null || !client.player.isPassenger());

		GpuBuffer ubo = uniformBuffer.currentBuffer();
		try (GpuBuffer.MappedView mapped = encoder.mapBuffer(ubo, false, true)) {
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
					width, height, config.strength, device.getBackendName());
		}

		try (RenderPass pass = encoder.createRenderPass(() -> "Motion blur",
				blurTargetView, OptionalInt.empty())) {
			pass.setPipeline(PIPELINE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("MotionBlurUniforms", ubo);
			pass.bindTexture("MainSampler", colorView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.bindTexture("MainDepthSampler", depthView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(0, 3);
		}

		// Blit the result back onto the main framebuffer via a fullscreen pass.
		// (Not copyTextureToTexture: VulkanMod 0.6.x has that as an unimplemented
		// no-op, which made the blur invisible on the Vulkan backend.)
		try (RenderPass pass = encoder.createRenderPass(() -> "Motion blur blit",
				colorView, OptionalInt.empty())) {
			pass.setPipeline(RenderPipelines.TRACY_BLIT);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("InSampler", blurTargetView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(0, 3);
		}
		uniformBuffer.rotate();
		return true;
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

	private static GpuTextureView fallbackDepthView(GpuDevice device, GpuTexture depthTexture) {
		if (depthTexture == null || depthTexture.isClosed()) {
			return null;
		}
		if (depthFallbackSource != depthTexture || depthFallbackView == null) {
			if (depthFallbackView != null) {
				depthFallbackView.close();
			}
			depthFallbackView = device.createTextureView(depthTexture);
			depthFallbackSource = depthTexture;
		}
		return depthFallbackView;
	}

	private static GpuTexture depthFallbackSource;
	private static GpuTextureView depthFallbackView;

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
