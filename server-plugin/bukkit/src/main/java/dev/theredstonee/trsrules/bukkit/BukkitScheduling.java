package dev.theredstonee.trsrules.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Classic single main thread (Spigot, Paper, Purpur). */
final class BukkitScheduling implements Scheduling {
	private final Plugin plugin;

	BukkitScheduling(Plugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public void runLater(Player player, long ticks, Runnable task) {
		Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1L, ticks));
	}

	@Override
	public void run(Player player, Runnable task) {
		if (Bukkit.isPrimaryThread()) {
			task.run();
		} else {
			Bukkit.getScheduler().runTask(plugin, task);
		}
	}
}
