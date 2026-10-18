package com.wayopps.app

import kotlin.math.abs

// Pure Kotlin (no Android types): mapped points, lines and polygons and their geometry.

enum class FeatureKind(val label: String) { POINT("Point"), LINE("Line"), POLYGON("Polygon") }

data class Vertex(val lat: Double, val lon: Double, val alt: Double? = null, val acc: Double? = null)

data class MapFeature(
    val id: String,
    val kind: FeatureKind,
    val label: String,
    val type: String,
    val note: String,
    val vertices: List<Vertex>,
    val createdAt: Long
) {
    val lengthM: Double get() = FeatureGeometry.lengthM(vertices, closed = kind == FeatureKind.POLYGON)
    val areaM2: Double get() = if (kind == FeatureKind.POLYGON) FeatureGeometry.areaM2(vertices) else 0.0
}

object FeatureGeometry {
    /** Length along the vertices (perimeter when [closed]), WGS84 ground distance. */
    fun lengthM(v: List<Vertex>, closed: Boolean): Double {
        if (v.size < 2) return 0.0
        var d = 0.0
        for (i in 0 until v.size - 1) d += Geodesy.groundDistance(v[i].lat, v[i].lon, v[i + 1].lat, v[i + 1].lon)
        if (closed && v.size > 2) d += Geodesy.groundDistance(v.last().lat, v.last().lon, v[0].lat, v[0].lon)
        return d
    }

    /** Polygon area, m², shoelace in the local tangent plane at the first vertex. */
    fun areaM2(v: List<Vertex>): Double {
        if (v.size < 3) return 0.0
        val o = v[0]
        val xy = v.map { Geodesy.toEN(it.lat, it.lon, o.lat, o.lon) }
        var sum = 0.0
        for (i in xy.indices) {
            val j = (i + 1) % xy.size
            sum += xy[i].first * xy[j].second - xy[j].first * xy[i].second
        }
        return abs(sum) / 2
    }

    /** Where to put a feature's label: the point, the middle of a line, the vertex average of a polygon. */
    fun labelPosition(f: MapFeature): LatLon? {
        val v = f.vertices
        if (v.isEmpty()) return null
        return when (f.kind) {
            FeatureKind.POINT -> LatLon(v[0].lat, v[0].lon)
            FeatureKind.LINE -> PathFollower(v.map { LatLon(it.lat, it.lon) }).let { pf ->
                if (v.size >= 2) pf.pointAt(pf.totalMeters / 2) else LatLon(v[0].lat, v[0].lon)
            }
            FeatureKind.POLYGON -> LatLon(v.sumOf { it.lat } / v.size, v.sumOf { it.lon } / v.size)
        }
    }

    fun formatArea(m2: Double): String = if (m2 >= 10_000) "${"%.3f".format(m2 / 10_000)} ha" else "${"%.1f".format(m2)} m²"
}
