package dev.theredstonee.trsclient.online;

import net.minecraft.client.model.ModelPlayer;
import net.minecraft.entity.Entity;

/** Vanilla-Spielermodell, das nach {@code setRotationAngles} die Emote-Pose anlegt (siehe {@link LegacyEmotes}). */
public final class EmoteModelPlayer extends ModelPlayer {
	public EmoteModelPlayer(float size, boolean slimArms) {
		super(size, slimArms);
	}

	@Override
	public void setRotationAngles(float limbSwing, float limbAmount, float ageInTicks, float headYaw, float headPitch,
			float scale, Entity entity) {
		LegacyEmotes.before(this);
		super.setRotationAngles(limbSwing, limbAmount, ageInTicks, headYaw, headPitch, scale, entity);
		if (LegacyEmotes.after(this, entity, ageInTicks)) {
			// Die Überzüge (Ärmel, Hosenbeine, Jacke) hat Vanilla schon kopiert – mit der neuen Pose noch einmal.
			copyModelAngles(bipedLeftLeg, bipedLeftLegwear);
			copyModelAngles(bipedRightLeg, bipedRightLegwear);
			copyModelAngles(bipedLeftArm, bipedLeftArmwear);
			copyModelAngles(bipedRightArm, bipedRightArmwear);
			copyModelAngles(bipedBody, bipedBodyWear);
		}
	}
}
