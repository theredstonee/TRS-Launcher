package dev.theredstonee.trs.game.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McOptionsTest {
    @Test
    fun defaultBecomesVulkanButPlayerChoiceStays() {
        assertEquals(listOf("a:1", "preferredGraphicsBackend:\"vulkan\""), McOptions.withVulkan(listOf("a:1", "preferredGraphicsBackend:\"default\"")))
        assertEquals(listOf("preferredGraphicsBackend:\"vulkan\""), McOptions.withVulkan(emptyList()))
        assertNull(McOptions.withVulkan(listOf("preferredGraphicsBackend:\"opengl\"")))
        assertNull(McOptions.withVulkan(listOf("preferredGraphicsBackend:\"vulkan\"")))
    }
}
