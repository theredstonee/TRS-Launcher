package dev.theredstonee.trsrules.bukkit;

import org.bukkit.entity.Player;

/** Runs work on the thread that may act on a player: main thread (Bukkit) or the player's region thread (Folia). */
public interface Scheduling {
	void runLater(Player player, long ticks, Runnable task);

	void run(Player player, Runnable task);
}
