package dev.theredstonee.trsclient.core.pack;

import java.util.Arrays;
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
	private static final List<Entry> ALL = Arrays.asList(VANILLA, FAITH, PVP, OLD);

	private static List<String> ids(List<Entry> entries) {
		return entries.stream().map(Entry::id).collect(java.util.stream.Collectors.toList());
	}

	@Test
	void enabledFirstByPriorityThenAvailableAlphabetically() {
		List<String> enabled = Arrays.asList("vanilla", "file/PvP Pack");
		assertEquals(Arrays.asList("file/PvP Pack", "vanilla", "file/old", "file/Faithful 32x.zip"),
				ids(PackList.visible(ALL, enabled, Filter.ALL, "")));
		assertEquals(Arrays.asList("file/PvP Pack", "vanilla"), ids(PackList.visible(ALL, enabled, Filter.ENABLED, null)));
		assertEquals(Arrays.asList("file/old", "file/Faithful 32x.zip"), ids(PackList.visible(ALL, enabled, Filter.AVAILABLE, " ")));
	}

	@Test
	void searchMatchesAllWordsCaseInsensitiveInTitleIdOrDescription() {
		List<String> enabled = Arrays.asList("vanilla");
		assertEquals(Arrays.asList("file/Faithful 32x.zip"), ids(PackList.visible(ALL, enabled, Filter.ALL, "FAITH")));
		assertEquals(Arrays.asList("file/PvP Pack"), ids(PackList.visible(ALL, enabled, Filter.ALL, "kurze pvp")));
		assertEquals(Arrays.asList("file/old"), ids(PackList.visible(ALL, enabled, Filter.ALL, "programmer")));
		assertEquals(Arrays.asList(), ids(PackList.visible(ALL, enabled, Filter.ALL, "pvp faithful")));
	}

	@Test
	void toggleAddsWithHighestPriorityAndKeepsRequired() {
		List<String> enabled = Arrays.asList("vanilla", "file/PvP Pack");
		assertEquals(Arrays.asList("vanilla", "file/PvP Pack", "file/Faithful 32x.zip"), PackList.toggle(enabled, FAITH));
		assertEquals(Arrays.asList("vanilla"), PackList.toggle(enabled, PVP));
		assertEquals(enabled, PackList.toggle(enabled, VANILLA));
	}

	@Test
	void moveSwapsNeighboursButNotFixedPacks() {
		List<String> enabled = Arrays.asList("vanilla", "a", "b");
		assertEquals(Arrays.asList("vanilla", "b", "a"), PackList.move(enabled, "a", +1, Arrays.asList("vanilla")));
		assertEquals(enabled, PackList.move(enabled, "a", -1, Arrays.asList("vanilla")));
		assertEquals(enabled, PackList.move(enabled, "b", +1, Arrays.asList("vanilla")));
		assertEquals(enabled, PackList.move(enabled, "x", +1, Arrays.asList()));
	}

	@Test
	void filterCycles() {
		assertEquals(Filter.ENABLED, Filter.ALL.next());
		assertEquals(Filter.ALL, Filter.AVAILABLE.next());
	}
}
