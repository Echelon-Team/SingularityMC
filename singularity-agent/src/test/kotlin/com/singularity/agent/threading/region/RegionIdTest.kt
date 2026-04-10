package com.singularity.agent.threading.region

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RegionIdTest {

    @Test
    fun `fromBlockCoords computes correct region for origin`() {
        val id = RegionId.fromBlockCoords(blockX = 0, blockZ = 0, regionSizeBlocks = 128)
        assertEquals(0, id.x)
        assertEquals(0, id.z)
    }

    @Test
    fun `fromBlockCoords for block in first region positive`() {
        val id = RegionId.fromBlockCoords(blockX = 64, blockZ = 64, regionSizeBlocks = 128)
        assertEquals(0, id.x)
        assertEquals(0, id.z)
    }

    @Test
    fun `fromBlockCoords at region boundary goes to next region`() {
        val id = RegionId.fromBlockCoords(blockX = 128, blockZ = 0, regionSizeBlocks = 128)
        assertEquals(1, id.x)
        assertEquals(0, id.z)
    }

    @Test
    fun `fromBlockCoords for negative coordinates`() {
        val id = RegionId.fromBlockCoords(blockX = -1, blockZ = -1, regionSizeBlocks = 128)
        assertEquals(-1, id.x)
        assertEquals(-1, id.z)
    }

    @Test
    fun `fromBlockCoords for negative at exact boundary`() {
        val id = RegionId.fromBlockCoords(blockX = -128, blockZ = -128, regionSizeBlocks = 128)
        assertEquals(-1, id.x)
        assertEquals(-1, id.z)
    }

    @Test
    fun `fromBlockCoords for negative beyond boundary`() {
        val id = RegionId.fromBlockCoords(blockX = -129, blockZ = -129, regionSizeBlocks = 128)
        assertEquals(-2, id.x)
        assertEquals(-2, id.z)
    }

    @Test
    fun `neighbors returns 8 adjacent regions`() {
        val center = RegionId(0, 0)
        val neighbors = center.neighbors()
        assertEquals(8, neighbors.size)
        assertTrue(neighbors.contains(RegionId(-1, -1)))
        assertTrue(neighbors.contains(RegionId(0, -1)))
        assertTrue(neighbors.contains(RegionId(1, -1)))
        assertTrue(neighbors.contains(RegionId(-1, 0)))
        assertTrue(neighbors.contains(RegionId(1, 0)))
        assertTrue(neighbors.contains(RegionId(-1, 1)))
        assertTrue(neighbors.contains(RegionId(0, 1)))
        assertTrue(neighbors.contains(RegionId(1, 1)))
        assertFalse(neighbors.contains(center))
    }

    @Test
    fun `isAdjacent true for 4-way neighbors`() {
        val a = RegionId(0, 0)
        assertTrue(a.isAdjacent(RegionId(1, 0)))
        assertTrue(a.isAdjacent(RegionId(-1, 0)))
        assertTrue(a.isAdjacent(RegionId(0, 1)))
        assertTrue(a.isAdjacent(RegionId(0, -1)))
    }

    @Test
    fun `isAdjacent true for diagonal neighbors`() {
        val a = RegionId(0, 0)
        assertTrue(a.isAdjacent(RegionId(1, 1)))
        assertTrue(a.isAdjacent(RegionId(-1, -1)))
    }

    @Test
    fun `isAdjacent false for far regions`() {
        val a = RegionId(0, 0)
        assertFalse(a.isAdjacent(RegionId(2, 0)))
        assertFalse(a.isAdjacent(RegionId(0, 5)))
    }

    @Test
    fun `isAdjacent false for self`() {
        val a = RegionId(0, 0)
        assertFalse(a.isAdjacent(a))
    }

    @Test
    fun `equality and hashCode`() {
        val a = RegionId(1, 2)
        val b = RegionId(1, 2)
        val c = RegionId(2, 1)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `manhattanDistance between two regions`() {
        assertEquals(0, RegionId(0, 0).manhattanDistance(RegionId(0, 0)))
        assertEquals(2, RegionId(0, 0).manhattanDistance(RegionId(1, 1)))
        assertEquals(5, RegionId(0, 0).manhattanDistance(RegionId(2, 3)))
        assertEquals(5, RegionId(0, 0).manhattanDistance(RegionId(-2, -3)))
    }
}
