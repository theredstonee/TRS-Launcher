package dev.theredstonee.trsclient.compat;

import dev.theredstonee.trsclient.core.perf.GameOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Vanilla-Grafikoptionen für den Leistungs-Check und die FPS-Boost-Stufen (Mojmap-Bäume, eine Datei):
 * bis 1.18 einfache Felder (Nebenwirkungen wie Chunks neu bauen hier von Hand), ab 1.19 OptionInstance
 * (deren Rückruf erledigt das). Ab 1.21.11 ersetzt eine Grafik-Voreinstellung „Schnell/Schön/Fabelhaft“ –
 * die würde Sichtweite & Co. mit ändern, deshalb schaltet TRS dort nur die beiden Teiloptionen
 * „Verbesserte Transparenz“ (≙ Fabelhaft) und „Durchsichtiges Laub“ (≙ Schön).
 */
public final class PerfOptions implements GameOptions {
	private static Options options() {
		return Minecraft.getInstance().options;
	}

	@Override
	public int get(Opt opt) {
		Options o = options();
		if (o == null) return NONE;
		switch (opt) {
			case VSYNC:
				//? if >=1.19 {
				/*return o.enableVsync().get() ? 1 : 0;
				*///?} else
				return o.enableVsync ? 1 : 0;
			case VIEW_DISTANCE:
				//? if >=1.19 {
				/*return o.renderDistance().get();
				*///?} else
				return o.renderDistance;
			case SIMULATION_DISTANCE:
				//? if >=1.19 {
				/*return o.simulationDistance().get();
				*///?} elif >=1.18 {
				/*return o.simulationDistance;
				*///?} else
				return NONE;
			case GRAPHICS:
				//? if >=1.21.11 {
				/*return o.improvedTransparency().get() ? 2 : (o.cutoutLeaves().get() ? 1 : 0);
				*///?} elif >=1.19 {
				/*return o.graphicsMode().get().ordinal();
				*///?} elif >=1.16 {
				return o.graphicsMode.ordinal();
				//?} else
				/*return o.fancyGraphics ? 1 : 0;*/
			case CLOUDS:
				//? if >=1.19 {
				/*return o.cloudStatus().get().ordinal();
				*///?} else
				return o.renderClouds.ordinal();
			case PARTICLES:
				//? if >=1.19 {
				/*return o.particles().get().ordinal();
				*///?} else
				return o.particles.ordinal();
			case MIPMAP:
				//? if >=1.19 {
				/*return o.mipmapLevels().get();
				*///?} elif >=1.15 {
				return o.mipmapLevels;
				//?} else
				/*return NONE;*/
			case BIOME_BLEND:
				//? if >=1.19 {
				/*return o.biomeBlendRadius().get();
				*///?} else
				return o.biomeBlendRadius;
			case ENTITY_DISTANCE:
				//? if >=1.19 {
				/*return (int) Math.round(o.entityDistanceScaling().get() * 100);
				*///?} elif >=1.16 {
				return Math.round(o.entityDistanceScaling * 100);
				//?} else
				/*return NONE;*/
			case SMOOTH_LIGHTING:
				//? if >=1.19.3 {
				/*return o.ambientOcclusion().get() ? 2 : 0;
				*///?} elif >=1.19 {
				/*return o.ambientOcclusion().get().ordinal();
				*///?} else
				return o.ambientOcclusion.ordinal();
			case FULLSCREEN:
				//? if >=1.19 {
				/*return o.fullscreen().get() ? 1 : 0;
				*///?} else
				return o.fullscreen ? 1 : 0;
			case MAX_FPS:
				//? if >=1.19 {
				/*return o.framerateLimit().get();
				*///?} else
				return o.framerateLimit;
			default:
				return NONE;
		}
	}

	@Override
	public boolean set(Opt opt, int raw) {
		Options o = options();
		if (o == null || get(opt) == NONE) return false;
		int v = opt.clamp(raw);
		Minecraft mc = Minecraft.getInstance();
		switch (opt) {
			case VSYNC:
				//? if >=1.19 {
				/*o.enableVsync().set(v == 1);
				*///?} else {
				o.enableVsync = v == 1;
				Mc.window().updateVsync(v == 1);
				//?}
				return true;
			case VIEW_DISTANCE:
				//? if >=1.19 {
				/*o.renderDistance().set(v);
				*///?} else {
				o.renderDistance = v;
				mc.levelRenderer.needsUpdate();
				//?}
				return true;
			case SIMULATION_DISTANCE:
				//? if >=1.19 {
				/*o.simulationDistance().set(v);
				*///?} elif >=1.18 {
				/*o.simulationDistance = v;
				*///?}
				return true;
			case GRAPHICS:
				//? if >=1.21.11 {
				/*o.improvedTransparency().set(v >= 2);
				o.cutoutLeaves().set(v >= 1);
				*///?} elif >=1.19 {
				/*o.graphicsMode().set(net.minecraft.client.GraphicsStatus.values()[Math.min(v, 2)]);
				*///?} elif >=1.16 {
				o.graphicsMode = net.minecraft.client.GraphicsStatus.values()[Math.min(v, 2)];
				mc.levelRenderer.allChanged();
				//?} else {
				/*o.fancyGraphics = v >= 1;
				mc.levelRenderer.allChanged();
				*///?}
				return true;
			case CLOUDS:
				//? if >=1.19 {
				/*o.cloudStatus().set(net.minecraft.client.CloudStatus.values()[v]);
				*///?} else
				o.renderClouds = net.minecraft.client.CloudStatus.values()[v];
				return true;
			case PARTICLES:
				//? if >=1.21.2 {
				/*o.particles().set(net.minecraft.server.level.ParticleStatus.values()[v]);
				*///?} elif >=1.19 {
				/*o.particles().set(net.minecraft.client.ParticleStatus.values()[v]);
				*///?} else
				o.particles = net.minecraft.client.ParticleStatus.values()[v];
				return true;
			case MIPMAP:
				//? if >=1.19 {
				/*o.mipmapLevels().set(v);
				*///?} elif >=1.15 {
				o.mipmapLevels = v;
				//?}
				//? if >=1.15 {
				mc.updateMaxMipLevel(v);
				mc.delayTextureReload();
				//?}
				return true;
			case BIOME_BLEND:
				//? if >=1.19 {
				/*o.biomeBlendRadius().set(v);
				*///?} else {
				o.biomeBlendRadius = v;
				mc.levelRenderer.allChanged();
				//?}
				return true;
			case ENTITY_DISTANCE:
				//? if >=1.19 {
				/*o.entityDistanceScaling().set(v / 100.0);
				*///?} elif >=1.16 {
				o.entityDistanceScaling = v / 100f;
				//?}
				return true;
			case SMOOTH_LIGHTING:
				//? if >=1.19.3 {
				/*o.ambientOcclusion().set(v > 0);
				*///?} elif >=1.19 {
				/*o.ambientOcclusion().set(net.minecraft.client.AmbientOcclusionStatus.values()[v]);
				*///?} else {
				o.ambientOcclusion = net.minecraft.client.AmbientOcclusionStatus.values()[v];
				mc.levelRenderer.allChanged();
				//?}
				return true;
			case MAX_FPS:
				// In Zehnerschritten wie der Schieberegler; 260 = unbegrenzt.
				v = Math.round(v / 10f) * 10;
				//? if >=1.19 {
				/*o.framerateLimit().set(v);
				*///?} else {
				o.framerateLimit = v;
				Mc.window().setFramerateLimit(v);
				//?}
				return true;
			default:
				return false;
		}
	}

	@Override
	public boolean smoothLightingIsBoolean() {
		//? if >=1.19.3 {
		/*return true;
		*///?} else
		return false;
	}

	@Override
	public void save() {
		Options o = options();
		if (o != null) o.save();
	}

	@Override
	public String renderer() {
		try {
			//? if >=26.2 {
			/*return com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().name();
			*///?} elif >=1.21.5 {
			/*return com.mojang.blaze3d.systems.RenderSystem.getDevice().getRenderer();
			*///?} elif >=1.15 {
			return com.mojang.blaze3d.platform.GlUtil.getRenderer();
			//?} else
			/*return com.mojang.blaze3d.platform.GLX.getRenderer();*/
		} catch (RuntimeException | LinkageError e) {
			return "";
		}
	}

	@Override
	public String vendor() {
		try {
			//? if >=26.2 {
			/*return com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().vendorName();
			*///?} elif >=1.21.5 {
			/*return com.mojang.blaze3d.systems.RenderSystem.getDevice().getVendor();
			*///?} elif >=1.15 {
			return com.mojang.blaze3d.platform.GlUtil.getVendor();
			//?} else
			/*return com.mojang.blaze3d.platform.GLX.getVendor();*/
		} catch (RuntimeException | LinkageError e) {
			return "";
		}
	}
}
