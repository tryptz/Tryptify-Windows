package tf.monochrome.desktop.audio.dsp.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The master sits at index 4 in the middle of the bus list, so the index, the
 * number on the strip and the position on screen all differ past bus 4.
 */
class BusConfigTest {

    private fun bus(index: Int) = BusConfig(index = index, name = BusConfig.nameFor(index))

    @Test
    fun `numbers skip the master's index`() {
        assertEquals(listOf("Mix A", "FX A", "Mix B", "FX B", "Master", "Bus 5", "Bus 16"),
            listOf(0, 1, 2, 3, 4, 5, 16).map { BusConfig.nameFor(it) })
        assertEquals(5, bus(5).number)
        assertEquals(4, bus(3).number)
    }

    @Test
    fun `the four fixed buses are the DJ console`() {
        val buses = BusConfig.defaultBuses()
        val byIndex = buses.associateBy { it.index }
        // Mix A hears the player and feeds FX A; Mix B hears deck B and feeds FX B.
        assertEquals(mapOf(BusConfig.FX_A to 1f), byIndex[BusConfig.MIX_A]!!.sends)
        assertEquals(mapOf(BusConfig.FX_B to 1f), byIndex[BusConfig.MIX_B]!!.sends)
        assertEquals(BusConfig.DEFAULT_SENDS, byIndex[BusConfig.FX_A]!!.sends)
        assertEquals(BusConfig.DEFAULT_SENDS, byIndex[BusConfig.FX_B]!!.sends)
        assertTrue(byIndex[BusConfig.MIX_A]!!.inputEnabled)
        assertEquals(BusConfig.INPUT_PLAYER, byIndex[BusConfig.MIX_A]!!.inputSource)
        assertTrue(byIndex[BusConfig.MIX_B]!!.inputEnabled)
        assertEquals(BusConfig.INPUT_SIDE, byIndex[BusConfig.MIX_B]!!.inputSource)
        assertFalse(byIndex[BusConfig.FX_A]!!.inputEnabled || byIndex[BusConfig.FX_B]!!.inputEnabled)
        assertTrue(BusConfig.hearsDeckB(buses))
        assertFalse(BusConfig.hearsDeckB(buses.map { if (it.index == BusConfig.MIX_B) it.copy(muted = true) else it }))
        assertFalse(BusConfig.hearsDeckB((0..4).map { bus(it) }))
    }

    @Test
    fun `the master is shown last and the buses in number order`() {
        val buses = (0..7).map { bus(it) }
        assertEquals(listOf(0, 1, 2, 3, 5, 6, 7, 4), BusConfig.displayOrder(buses).map { it.index })
        assertEquals(7, BusConfig.mixBusCount(buses))
    }

    @Test
    fun `only the added buses can be removed`() {
        assertTrue((0..4).none { bus(it).isRemovable })
        assertTrue((5..16).all { bus(it).isRemovable })
        assertFalse(bus(3).isMaster)
        assertTrue(bus(4).isMaster)
    }

    @Test
    fun `exports say when they carry added buses`() {
        val five = """{"buses":[{},{},{},{},{}]}"""
        val six = """{"buses":[{},{},{},{},{},{}]}"""
        assertEquals(MixPresetFile.VERSION_FIXED_BUSES, MixPresetFile.of("a", five).version)
        assertEquals(MixPresetFile.VERSION_ADDED_BUSES, MixPresetFile.of("b", six).version)
        assertEquals(MixPresetFile.VERSION_FIXED_BUSES, MixPresetFile.versionFor("not json"))
    }
}
