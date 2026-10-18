package com.wayopps.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MapFeaturesTest {
    private fun v(e: Double, n: Double): Vertex {
        val (lat, lon) = Geodesy.fromEN(e, n, -26.7, 153.1)
        return Vertex(lat, lon)
    }

    private val square = listOf(v(0.0, 0.0), v(50.0, 0.0), v(50.0, 20.0), v(0.0, 20.0))

    @Test
    fun polygonAreaAndPerimeter() {
        val f = MapFeature("p", FeatureKind.POLYGON, "Paddock", "Boundary", "", square, 0)
        assertEquals(1000.0, f.areaM2, 0.05)
        assertEquals(140.0, f.lengthM, 0.01)
        assertEquals("1000.0 m²", FeatureGeometry.formatArea(f.areaM2).replace(',', '.'))
    }

    @Test
    fun lineLengthAndLabelAtMiddle() {
        val f = MapFeature("l", FeatureKind.LINE, "Fence", "Fence", "", listOf(v(0.0, 0.0), v(0.0, 100.0)), 0)
        assertEquals(100.0, f.lengthM, 0.01)
        assertEquals(0.0, f.areaM2, 0.0)
        val mid = FeatureGeometry.labelPosition(f)!!
        val (e, n) = Geodesy.toEN(mid.lat, mid.lon, -26.7, 153.1)
        assertEquals(0.0, e, 0.01)
        assertEquals(50.0, n, 0.05)
    }

    @Test
    fun degenerateFeatures() {
        assertEquals(0.0, FeatureGeometry.areaM2(square.take(2)), 0.0)
        assertEquals(0.0, FeatureGeometry.lengthM(square.take(1), closed = true), 0.0)
        val p = MapFeature("x", FeatureKind.POINT, "Tree", "Tree", "", square.take(1), 0)
        assertEquals(square[0].lat, FeatureGeometry.labelPosition(p)!!.lat, 0.0)
    }
}
