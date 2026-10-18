package com.jahleel.motionblur.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * RenderPipelines.register became private in 26.1. Registering keeps our
 * pipeline in the static pipeline list, so it is precompiled on resource
 * reload like every vanilla pipeline (and like the 1.21.11 build did).
 */
@Mixin(RenderPipelines.class)
public interface RenderPipelinesInvoker {
	@Invoker("register")
	static RenderPipeline motionblur$register(RenderPipeline pipeline) {
		throw new AssertionError("mixin invoker not applied");
	}
}
