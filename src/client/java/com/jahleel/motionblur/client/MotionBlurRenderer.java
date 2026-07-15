package com.jahleel.motionblur.client;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.MappableRingBuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

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
			.putVec3().putVec2().putFloat().putInt().putInt().putInt().putInt().putInt()
			.get();

	public static final RenderPipeline PIPELINE = RenderPipelines.register(
			RenderPipeline.builder()
					.withLocation(Identifier.of("motionblur", "pipeline/motion_blur"))
					.withVertexShader("core/screenquad")
					.withFragmentShader(Identifier.of("motionblur", "post/motion_blur"))
					.withSampler("MainSampler")
					.withSampler("MainDepthSampler")
					.withUniform("MotionBlurUniforms", UniformType.UNIFORM_BUFFER)
					.withoutBlend()
					.withDepthWrite(false)
					.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
					.withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
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

	/** Called from the WorldRenderer mixin at the start of every world render. */
	public static void captureFrame(Matrix4fc currentModelView, Matrix4fc currentProjection, Vec3d cameraPos) {
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
	public static void onWorldRendered(MinecraftClient client) {
		MotionBlurConfig config = MotionBlurClient.config;
		boolean wantBlur = config.enabled
				&& client.world != null
				&& (!config.pauseInGuis || client.currentScreen == null);

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

	private static void renderBlur(MinecraftClient client, MotionBlurConfig config) {
		Framebuffer main = client.getFramebuffer();
		GpuTexture color = main.getColorAttachment();
		GpuTextureView colorView = main.getColorAttachmentView();
		GpuTextureView depthView = main.getDepthAttachmentView();
		if (color == null || colorView == null || depthView == null) {
			return;
		}

		int width = main.textureWidth;
		int height = main.textureHeight;
		GpuDevice device = RenderSystem.getDevice();
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
		boolean firstPersonOnFoot = client.options.getPerspective().isFirstPerson()
				&& (client.player == null || !client.player.hasVehicle());

		try (GpuBuffer.MappedView mapped = encoder.mapBuffer(uniformBuffer.getBlocking(), false, true)) {
			Std140Builder.intoBuffer(mapped.data())
					.putMat4f(mvInverse)
					.putMat4f(projInverse)
					.putMat4f(prevModelView)
					.putMat4f(prevProjection)
					.putVec3((float) (camX - prevCamX), (float) (camY - prevCamY), (float) (camZ - prevCamZ))
					.putVec2(width, height)
					.putFloat(config.strength)
					.putInt(MAX_SAMPLES)
					.putInt(config.blurHand ? 1 : 0)
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
			pass.setUniform("MotionBlurUniforms", uniformBuffer.getBlocking());
			pass.bindTexture("MainSampler", colorView, RenderSystem.getSamplerCache().get(FilterMode.LINEAR));
			pass.bindTexture("MainDepthSampler", depthView, RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
			pass.draw(0, 3);
		}

		// Blit the result back onto the main framebuffer via a fullscreen pass.
		// (Not copyTextureToTexture: VulkanMod 0.6.x has that as an unimplemented
		// no-op, which made the blur invisible on the Vulkan backend.)
		try (RenderPass pass = encoder.createRenderPass(() -> "Motion blur blit",
				colorView, OptionalInt.empty())) {
			pass.setPipeline(RenderPipelines.TRACY_BLIT);
			RenderSystem.bindDefaultUniforms(pass);
			pass.bindTexture("InSampler", blurTargetView, RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
			pass.draw(0, 3);
		}
		uniformBuffer.rotate();
	}

	/**
	 * VulkanMod's backend cannot sample the main depth attachment (reads all
	 * zeros), so on Vulkan the shader falls back to pure camera-motion blur.
	 */
	private static boolean depthSamplingWorks() {
		String backend = RenderSystem.getDevice().getBackendName();
		return backend == null || !backend.toLowerCase().contains("vulkan");
	}

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
