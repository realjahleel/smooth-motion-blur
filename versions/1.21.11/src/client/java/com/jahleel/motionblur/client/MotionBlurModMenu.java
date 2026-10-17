package com.jahleel.motionblur.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu integration: config screen with toggle + strength slider. */
public final class MotionBlurModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return MotionBlurConfigScreen::new;
	}
}
