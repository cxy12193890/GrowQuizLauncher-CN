package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class VideoScenesTest {
    @Test fun commercialBuildDoesNotEnableThirdPartySceneCatalogues() {
        assertFalse(THIRD_PARTY_SCENES_ENABLED)
    }

    @Test fun parserReadsOnlyTheProvidedHttpsSceneMetadata() {
        val raw = "NASA Earth\\tEarth\\tNight view\\tnasa_test.mp4\\thttps://example.invalid/nasa_test.mp4\\n".toByteArray()
        val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.toByteArray()
        val scenes = parseVideoScenes(ByteArrayInputStream(compressed))
        assertEquals(1, scenes.size)
        assertEquals("nasa_test.mp4", scenes.single().file)
        assertTrue(scenes.single().url.startsWith("https://"))
    }
}