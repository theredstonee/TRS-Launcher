package dev.theredstonee.trsclient.core.screenshot.edit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unveränderlicher Stand des Bild-Editors: Drehung (Vierteldrehungen im Uhrzeigersinn), Zuschnitt und Zeichnungen.
 * Koordinaten gelten im „gedrehten Raum“ (Bild nach der Drehung, vor dem Zuschnitt). Rückgängig/Wiederholen tauscht nur
 * solche Stände – das Originalbild bleibt unberührt.
 */
public final class EditState {
	/** Kleinste Zuschnitt-Kante in Bildpixeln. */
	public static final int MIN_CROP = 8;
	/** Höchstens so viele Zeichnungen (Schutz gegen endlose Freihand-Striche). */
	public static final int MAX_SHAPES = 400;

	/** Größe des Originalbildes (vor Drehung). */
	public final int baseW, baseH;
	public final int rotation;
	public final int cropX, cropY, cropW, cropH;
	public final List<Shape> shapes;

	private EditState(int baseW, int baseH, int rotation, int cropX, int cropY, int cropW, int cropH, List<Shape> shapes) {
		this.baseW = baseW;
		this.baseH = baseH;
		this.rotation = ((rotation % 4) + 4) % 4;
		int rw = this.rotation % 2 == 0 ? baseW : baseH;
		int rh = this.rotation % 2 == 0 ? baseH : baseW;
		int cw = clamp(cropW, Math.min(MIN_CROP, rw), rw);
		int ch = clamp(cropH, Math.min(MIN_CROP, rh), rh);
		this.cropX = clamp(cropX, 0, rw - cw);
		this.cropY = clamp(cropY, 0, rh - ch);
		this.cropW = cw;
		this.cropH = ch;
		this.shapes = Collections.unmodifiableList(new ArrayList<>(shapes));
	}

	/** Ausgangsstand: nichts gedreht, alles sichtbar, keine Zeichnungen. */
	public static EditState initial(int w, int h) {
		return new EditState(w, h, 0, 0, 0, w, h, Collections.<Shape>emptyList());
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** Breite im gedrehten Raum. */
	public int width() {
		return rotation % 2 == 0 ? baseW : baseH;
	}

	public int height() {
		return rotation % 2 == 0 ? baseH : baseW;
	}

	public boolean cropped() {
		return cropX != 0 || cropY != 0 || cropW != width() || cropH != height();
	}

	/** Unverändert gegenüber dem Original? */
	public boolean pristine() {
		return rotation == 0 && !cropped() && shapes.isEmpty();
	}

	public EditState withShape(Shape s) {
		if (s == null || shapes.size() >= MAX_SHAPES) return this;
		List<Shape> list = new ArrayList<>(shapes);
		list.add(s);
		return new EditState(baseW, baseH, rotation, cropX, cropY, cropW, cropH, list);
	}

	public EditState withCrop(int x, int y, int w, int h) {
		return new EditState(baseW, baseH, rotation, x, y, w, h, shapes);
	}

	/** Zuschnitt aufheben. */
	public EditState uncropped() {
		return new EditState(baseW, baseH, rotation, 0, 0, width(), height(), shapes);
	}

	/** 90° im Uhrzeigersinn: Zeichnungen und Zuschnitt drehen mit. */
	public EditState rotatedClockwise() {
		int oldH = height();
		List<Shape> list = new ArrayList<>(shapes.size());
		for (Shape s : shapes) list.add(s.rotated(oldH));
		// Zuschnitt-Rechteck (x, y, w, h) → (H − y − h, x, h, w).
		return new EditState(baseW, baseH, rotation + 1, oldH - cropY - cropH, cropX, cropH, cropW, list);
	}

	/** Größe des Ergebnisses {w, h}. */
	public int[] outputSize() {
		return new int[]{cropW, cropH};
	}
}
