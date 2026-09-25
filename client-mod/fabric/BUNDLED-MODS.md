# Built-in optimizations of the TRS Client (Fabric) – third-party notices

The Fabric builds of the TRS Client embed the following free performance mods unchanged as nested jars
(Fabric "Jar-in-Jar", `META-INF/jars/`). Each nested jar keeps its own license file. The exact file, version
and SHA-512 per Minecraft version are pinned in `bundled-mods.json` (source: Modrinth).

| Mod | License | Source code |
|-----|---------|-------------|
| Lithium (CaffeineMC) | GNU LGPL v3.0 | https://github.com/CaffeineMC/lithium |
| FerriteCore (malte0811) | MIT | https://github.com/malte0811/FerriteCore |
| ImmediatelyFast (RaphiMC) | GNU LGPL v3.0 or later | https://github.com/RaphiMC/ImmediatelyFast |
| ModernFix (embeddedt), Minecraft 1.20+ | GNU LGPL v3.0 | https://github.com/embeddedt/ModernFix |
| BadOptimizations (ItsThosea) | MIT | https://github.com/ItsThosea/BadOptimizations |

The LGPL-licensed mods are distributed unmodified; their complete source code is available at the links
above, and you may replace a nested jar with your own build of the same mod (put your version into the
`mods` folder – Fabric loads the newest version of a mod ID). The TRS Client itself is licensed under the
GNU GPL v3.0.

Not embedded on purpose: Sodium (PolyForm Shield license), Entity Culling (tr7zw Protective License, non
commercial), Iris (needs Sodium), More Culling (needs Cloth Config). The TRS Launcher's "FPS Boost" preset still
installs them as separate mods where available.

Switching off: TRS menu → Performance → "Built-in optimizations". Takes effect on the next start through the
TRS Launcher (it starts Fabric with `-Dfabric.debug.disableModIds=…` for the embedded mods you did not install
yourself).
