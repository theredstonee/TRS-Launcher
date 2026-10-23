package dev.theredstonee.trsrules.bukkit.folia;

import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import dev.theredstonee.trsrules.bukkit.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Folia: work for a player runs on the player's region thread (entity scheduler). Loaded by reflection only when
 * Folia is detected.
 */
public final class FoliaScheduling implements Scheduling {
	private final Plugin plugin;

	public FoliaScheduling(Plugin plugin) {
		this.plugin = plugin;
	}

	private static Consumer<ScheduledTask> wrap(final Runnable task) {
		return new Consumer<ScheduledTask>() {
			@Override
			public void accept(ScheduledTask t) {
				task.run();
			}
		};
	}

	@Override
	public void runLater(Player player, long ticks, Runnable task) {
		// retired = null: the player left – nothing to do then
		player.getScheduler().runDelayed(plugin, wrap(task), null, Math.max(1L, ticks));
	}

	@Override
	public void run(Player player, Runnable task) {
		if (Bukkit.isOwnedByCurrentRegion(player)) {
			task.run();
		} else {
			player.getScheduler().run(plugin, wrap(task), null);
		}
	}
}
