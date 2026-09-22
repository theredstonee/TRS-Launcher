package dev.theredstonee.trsclient.core.pack;

import dev.theredstonee.trsclient.core.pack.PackList.Entry;
import dev.theredstonee.trsclient.core.pack.PackList.Filter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackListTest {
	private static final Entry VANILLA = new Entry("vanilla", "Standard", "Das Standard-Aussehen", true, true);
	private static final Entry FAITH = new Entry("file/Faithful 32x.zip", "Faithful 32x", "Doppelte Auflösung", false, true);
	private static final Entry PVP = new Entry("file/PvP Pack", "Bedrock PvP", "Kurze Schwerter", false, true);
	private static final Entry OLD = new Entry("file/old", "Alter Stil", "Programmer Art", false, false);
	private static final List<Entry> ALL = List.of(VANILLA, FAITH, PVP, OLD);

	private static List<String> ids(List<Entry> entries) {
		return entries.stream().map(Entry::id).toList();
	}

	@Test
	void enabledFirstByPriorityThenAvailableAlphabetically() {
		List<String> enabled = List.of("vanilla", "file/PvP Pack");
		assertEquals(List.of("file/PvP Pack", "vanilla", "file/old", "file/Faithful 32x.zip"),
				ids(PackList.visible(ALL, enabled, Filter.ALL, "")));
		assertEquals(List.of("file/PvP Pack", "vanilla"), ids(PackList.visible(ALL, enabled, Filter.ENABLED, null)));
		assertEquals(List.of("file/old", "file/Faithful 32x.zip"), ids(PackList.visible(ALL, enabled, Filter.AVAILABLE, " ")));
	}

	@Test
	void searchMatchesAllWordsCaseInsensitiveInTitleIdOrDescription() {
		List<String> enabled = List.of("vanilla");
		assertEquals(List.of("file/Faithful 32x.zip"), ids(PackList.visible(ALL, enabled, Filter.ALL, "FAITH")));
		assertEquals(List.of("file/PvP Pack"), ids(PackList.visible(ALL, enabled, Filter.ALL, "kurze pvp")));
		assertEquals(List.of("file/old"), ids(PackList.visible(ALL, enabled, Filter.ALL, "programmer")));
		assertEquals(List.of(), ids(PackList.visible(ALL, enabled, Filter.ALL, "pvp faithful")));
	}

	@Test
	void toggleAddsWithHighestPriorityAndKeepsRequired() {
		List<String> enabled = List.of("vanilla", "file/PvP Pack");
		assertEquals(List.of("vanilla", "file/PvP Pack", "file/Faithful 32x.zip"), PackList.toggle(enabled, FAITH));
		assertEquals(List.of("vanilla"), PackList.toggle(enabled, PVP));
		assertEquals(enabled, PackList.toggle(enabled, VANILLA));
	}

	@Test
	void moveSwapsNeighboursButNotFixedPacks() {
		List<String> enabled = List.of("vanilla", "a", "b");
		assertEquals(List.of("vanilla", "b", "a"), PackList.move(enabled, "a", +1, List.of("vanilla")));
		assertEquals(enabled, PackList.move(enabled, "a", -1, List.of("vanilla")));
		assertEquals(enabled, PackList.move(enabled, "b", +1, List.of("vanilla")));
		assertEquals(enabled, PackList.move(enabled, "x", +1, List.of()));
	}

	@Test
	void filterCycles() {
		assertEquals(Filter.ENABLED, Filter.ALL.next());
		assertEquals(Filter.ALL, Filter.AVAILABLE.next());
	}
}
