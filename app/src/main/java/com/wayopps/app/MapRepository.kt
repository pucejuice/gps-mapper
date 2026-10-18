package com.wayopps.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Shared map state: the crosshair (map centre) and requests to zoom to something. */
object MapViewState {
    /** Current map centre, i.e. where the crosshair is. */
    val center = MutableStateFlow<LatLon?>(null)
    /** Set to points to zoom the map to them; the map clears it once done. */
    val focusRequest = MutableStateFlow<List<LatLon>?>(null)

    fun focus(points: List<LatLon>) {
        if (points.isNotEmpty()) focusRequest.value = points
    }
}

/**
 * Mapped features (points, lines, polygons) with labels, kept on the phone so they
 * survive restarts. A line or polygon is built vertex by vertex as a draft, then saved.
 */
object MapRepository {

    data class Draft(val kind: FeatureKind, val vertices: List<Vertex>)

    private const val STORE_FILE = "map_features_v1.json"

    private val _features = MutableStateFlow<List<MapFeature>>(emptyList())
    val features = _features.asStateFlow()

    private val _draft = MutableStateFlow<Draft?>(null)
    val draft = _draft.asStateFlow()

    private var appContext: Context? = null

    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        load()
    }

    // ---------------- editing ----------------
    fun addPoint(v: Vertex, label: String, type: String, note: String) {
        add(FeatureKind.POINT, listOf(v), label, type, note)
    }

    fun startDraft(kind: FeatureKind) {
        _draft.value = Draft(kind, emptyList())
    }

    fun addVertex(v: Vertex) {
        val d = _draft.value ?: return
        _draft.value = d.copy(vertices = d.vertices + v)
    }

    fun undoVertex() {
        val d = _draft.value ?: return
        _draft.value = d.copy(vertices = d.vertices.dropLast(1))
    }

    fun cancelDraft() {
        _draft.value = null
    }

    /** Saves the draft as a feature; returns false if it doesn't have enough vertices. */
    fun finishDraft(label: String, type: String, note: String): Boolean {
        val d = _draft.value ?: return false
        val min = if (d.kind == FeatureKind.POLYGON) 3 else 2
        if (d.vertices.size < min) return false
        add(d.kind, d.vertices, label, type, note)
        _draft.value = null
        return true
    }

    private fun add(kind: FeatureKind, v: List<Vertex>, label: String, type: String, note: String) {
        val f = MapFeature(
            id = "F-${System.currentTimeMillis()}-${(Math.random() * 1e5).toInt()}",
            kind = kind,
            label = label.trim().ifEmpty { defaultLabel(kind, type) },
            type = type,
            note = note.trim(),
            vertices = v,
            createdAt = System.currentTimeMillis()
        )
        _features.value = _features.value + f
        save()
    }

    fun defaultLabel(kind: FeatureKind, type: String): String {
        val base = type.ifBlank { kind.label }
        val n = _features.value.count { it.type == type && it.kind == kind } + 1
        return "$base $n"
    }

    fun update(id: String, label: String, type: String, note: String) {
        _features.value = _features.value.map {
            if (it.id == id) it.copy(label = label.trim().ifEmpty { it.label }, type = type, note = note.trim()) else it
        }
        save()
    }

    fun delete(id: String) {
        _features.value = _features.value.filterNot { it.id == id }
        save()
    }

    fun clearAll() {
        _features.value = emptyList()
        _draft.value = null
        save()
    }

    // ---------------- persistence ----------------
    private fun file(): File? = appContext?.let { File(it.filesDir, STORE_FILE) }

    private fun save() {
        val f = file() ?: return
        val arr = JSONArray()
        for (feat in _features.value) arr.put(JSONObject().apply {
            put("id", feat.id); put("kind", feat.kind.name); put("label", feat.label); put("type", feat.type)
            put("note", feat.note); put("t", feat.createdAt)
            put("v", JSONArray().apply {
                for (v in feat.vertices) put(JSONArray().apply {
                    put(v.lat); put(v.lon); put(v.alt ?: JSONObject.NULL); put(v.acc ?: JSONObject.NULL)
                })
            })
        })
        try { f.writeText(arr.toString()) } catch (e: Exception) { /* storage full: keep in memory */ }
    }

    private fun load() {
        val f = file() ?: return
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            _features.value = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val va = o.getJSONArray("v")
                MapFeature(
                    id = o.getString("id"),
                    kind = FeatureKind.valueOf(o.getString("kind")),
                    label = o.getString("label"),
                    type = o.optString("type"),
                    note = o.optString("note"),
                    vertices = (0 until va.length()).map { j ->
                        val p = va.getJSONArray(j)
                        Vertex(p.getDouble(0), p.getDouble(1), if (p.isNull(2)) null else p.getDouble(2), if (p.isNull(3)) null else p.getDouble(3))
                    },
                    createdAt = o.optLong("t")
                )
            }
        } catch (e: Exception) {
            // Unreadable file: start empty rather than crash
        }
    }

    // ---------------- export ----------------
    fun exportGeoJson(): String {
        val features = JSONArray()
        for (f in _features.value) {
            fun coord(v: Vertex) = JSONArray().apply { put(v.lon); put(v.lat); v.alt?.let { put(Math.round(it * 100) / 100.0) } }
            val geometry = JSONObject().apply {
                when (f.kind) {
                    FeatureKind.POINT -> { put("type", "Point"); put("coordinates", coord(f.vertices.first())) }
                    FeatureKind.LINE -> { put("type", "LineString"); put("coordinates", JSONArray().apply { f.vertices.forEach { put(coord(it)) } }) }
                    FeatureKind.POLYGON -> {
                        put("type", "Polygon")
                        put("coordinates", JSONArray().put(JSONArray().apply {
                            f.vertices.forEach { put(coord(it)) }
                            put(coord(f.vertices.first())) // close the ring
                        }))
                    }
                }
            }
            features.put(JSONObject().apply {
                put("type", "Feature")
                put("properties", JSONObject().apply {
                    put("label", f.label); put("type", f.type); put("note", f.note); put("kind", f.kind.name.lowercase())
                    when (f.kind) {
                        FeatureKind.LINE -> put("length_m", Math.round(f.lengthM * 100) / 100.0)
                        FeatureKind.POLYGON -> {
                            put("area_m2", Math.round(f.areaM2 * 100) / 100.0)
                            put("area_ha", Math.round(f.areaM2) / 10_000.0)
                            put("perimeter_m", Math.round(f.lengthM * 100) / 100.0)
                        }
                        FeatureKind.POINT -> f.vertices.first().acc?.let { put("accuracy_m", Math.round(it * 100) / 100.0) }
                    }
                })
                put("geometry", geometry)
            })
        }
        return JSONObject().apply { put("type", "FeatureCollection"); put("features", features) }.toString(2)
    }
}
