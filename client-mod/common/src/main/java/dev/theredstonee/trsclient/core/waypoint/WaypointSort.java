package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.module.ChoiceSetting;

/** Sortierung der Wegpunkt-Listen. */
public enum WaypointSort implements ChoiceSetting.Option {
	DISTANCE("Distance"),
	NAME("Name"),
	CREATED("Newest");

	private final String label;

	WaypointSort(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
