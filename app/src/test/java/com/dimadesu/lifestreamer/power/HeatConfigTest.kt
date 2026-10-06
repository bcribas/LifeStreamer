package com.dimadesu.lifestreamer.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatConfigTest {

    @Test
    fun `steps out of order are made to rise`() {
        val config = HeatConfig(stepsC = listOf(48, 45, 45)).normalized()
        assertEquals(listOf(48, 49, 50), config.stepsC)
    }

    @Test
    fun `the hot step never gives more than the warm one`() {
        val config = HeatConfig(warmFps = 20, hotFps = 25, warmKbps = 3000, hotKbps = 4000).normalized()
        assertEquals(20, config.hotFps)
        assertEquals(3000, config.hotKbps)
    }

    @Test
    fun `values are kept in their range`() {
        val config = HeatConfig(stepsC = listOf(10, 99, 120), warmFps = 60, coolHoldMinutes = 0).normalized()
        assertEquals(listOf(35, 59, 60), config.stepsC)
        assertEquals(30, config.warmFps)
        assertEquals(1, config.coolHoldMinutes)
    }

    @Test
    fun `setting a step moves the others out of its way`() {
        val (up, movedUp) = HeatConfig.orderSteps(listOf(44, 47, 50), changed = 0)
        assertEquals(listOf(44, 47, 50), up)
        assertFalse(movedUp)

        val (raised, moved) = HeatConfig.orderSteps(listOf(49, 47, 50), changed = 0)
        assertEquals(listOf(49, 50, 51), raised)
        assertTrue(moved)

        val (lowered, _) = HeatConfig.orderSteps(listOf(44, 47, 43), changed = 2)
        assertEquals(listOf(41, 42, 43), lowered)
    }

    @Test
    fun `each step says what it gives up`() {
        val config = HeatConfig()
        assertEquals("Cameras at 24 fps, at most 4 Mbps, preview off", config.describe(1))
        assertTrue(config.summary().startsWith("Battery ≥ 44 °C: Cameras at 24 fps"))
        assertTrue(HeatConfig(warmKbps = 3500).describe(1).contains("3.5 Mbps"))
    }
}
