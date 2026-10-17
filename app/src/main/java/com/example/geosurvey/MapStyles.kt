package com.example.geosurvey

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/** XYZ tile source built from a URL template containing {z}, {x} and {y}. */
private class TemplateTileSource(
    name: String,
    maxZoom: Int,
    templates: Array<String>,
    copyright: String
) : OnlineTileSourceBase(name, 0, maxZoom, 256, ".png", templates, copyright) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl
            .replace("{z}", MapTileIndex.getZoom(pMapTileIndex).toString())
            .replace("{x}", MapTileIndex.getX(pMapTileIndex).toString())
            .replace("{y}", MapTileIndex.getY(pMapTileIndex).toString())
}

// All layers come from Esri's ArcGIS Online tile server (no API key, works with osmdroid).
private fun esri(service: String, name: String, maxZoom: Int, copyright: String): ITileSource = TemplateTileSource(
    name = name,
    maxZoom = maxZoom,
    templates = arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/$service/MapServer/tile/{z}/{y}/{x}"),
    copyright = copyright
)

private val esriStreets = esri("World_Street_Map", "EsriWorldStreetMap", 19, "Map © Esri, HERE, Garmin, OpenStreetMap contributors")
private val esriImagery = esri("World_Imagery", "EsriWorldImagery", 19, "Imagery © Esri, Maxar, Earthstar Geographics")
private val esriTopo = esri("World_Topo_Map", "EsriWorldTopoMap", 19, "Map © Esri, HERE, Garmin, OpenStreetMap contributors")

/** Greyscale + invert + dim: turns the street map into a dark basemap with light roads. */
private val darkMapFilter = ColorMatrixColorFilter(
    ColorMatrix(
        floatArrayOf(
            -0.299f * 0.8f, -0.587f * 0.8f, -0.114f * 0.8f, 0f, 205f,
            -0.299f * 0.8f, -0.587f * 0.8f, -0.114f * 0.8f, 0f, 208f,
            -0.299f * 0.8f, -0.587f * 0.8f, -0.114f * 0.8f, 0f, 218f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

enum class MapStyle(val label: String) {
    DARK("Dark"),
    STREETS("Streets"),
    SATELLITE("Satellite"),
    TOPO("Topographic");

    fun tileSource(): ITileSource = when (this) {
        DARK, STREETS -> esriStreets
        SATELLITE -> esriImagery
        TOPO -> esriTopo
    }

    fun tileColorFilter(): ColorMatrixColorFilter? = if (this == DARK) darkMapFilter else null

    companion object {
        fun fromName(name: String?, darkTheme: Boolean): MapStyle =
            values().firstOrNull { it.name == name } ?: if (darkTheme) DARK else STREETS
    }
}
