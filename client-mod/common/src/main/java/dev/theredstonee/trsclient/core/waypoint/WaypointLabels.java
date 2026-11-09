package dev.theredstonee.trsclient.core.waypoint;

import dev.theredstonee.trsclient.core.module.ChoiceSetting;

/** Wann Name und Entfernung eines Wegpunkts in der Welt erscheinen. */
public enum WaypointLabels implements ChoiceSetting.Option {
	LOOK("When looking at it"),
	ALWAYS("Always"),
	NEVER("Never");

	private final String label;

	WaypointLabels(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}
}
