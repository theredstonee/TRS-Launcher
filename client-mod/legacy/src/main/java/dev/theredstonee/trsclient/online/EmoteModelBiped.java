package dev.theredstonee.trsclient.online;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.entity.Entity;

/** Rüstungsmodell, das die Emote-Pose des Trägers übernimmt (siehe {@link LegacyEmotes}). */
public final class EmoteModelBiped extends ModelBiped {
	public EmoteModelBiped(float size) {
		super(size);
	}

	@Override
	public void setRotationAngles(float limbSwing, float limbAmount, float ageInTicks, float headYaw, float headPitch,
			float scale, Entity entity) {
		LegacyEmotes.before(this);
		super.setRotationAngles(limbSwing, limbAmount, ageInTicks, headYaw, headPitch, scale, entity);
		LegacyEmotes.after(this, entity, ageInTicks);
	}
}
