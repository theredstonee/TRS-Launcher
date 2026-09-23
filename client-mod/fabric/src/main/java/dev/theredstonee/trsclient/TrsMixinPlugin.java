package dev.theredstonee.trsclient;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Lädt die Mixins, die nur der Selbsttest braucht (echte Maus-/Tastatur-Eingabe), ausschließlich
 * mit {@code -Dtrsclient.autotest=true}. Im normalen Spiel greifen sie nirgends ein – so kann
 * ein Test-Hilfsmittel nie den Spielstart gefährden.
 */
public final class TrsMixinPlugin implements IMixinConfigPlugin {
	private static final String[] TEST_ONLY = {"MouseHandlerAccessor", "KeyboardHandlerAccessor"};

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		for (String name : TEST_ONLY) {
			if (mixinClassName.endsWith("." + name)) return Boolean.getBoolean("trsclient.autotest");
		}
		return true;
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
