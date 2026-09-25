package dev.theredstonee.trsclient.menus;

/** Vom Screen-Mixin umgesetzt: fügt einem Vanilla-Bildschirm einen Knopf hinzu (wie {@code addRenderableWidget}). */
public interface WidgetHost {
	void trsclient$addWidget(Object widget);
}
