package com.example.geosurvey

import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex

/** XYZ tile source built from a URL template containing {z}, {x} and {y}. */
private class TemplateTileSource(
    name: String,
    maxZoom: Int,
    private val templates: Array<String>,
    copyright: String
) : OnlineTileSourceBase(name, 0, maxZoom, 256, ".png", templates, copyright) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl
            .replace("{z}", MapTileIndex.getZoom(pMapTileIndex).toString())
            .replace("{x}", MapTileIndex.getX(pMapTileIndex).toString())
            .replace("{y}", MapTileIndex.getY(pMapTileIndex).toString())
}

private fun carto(style: String, name: String) = TemplateTileSource(
    name = name,
    maxZoom = 20,
    templates = arrayOf("a", "b", "c", "d").map { "https://$it.basemaps.cartocdn.com/$style/{z}/{x}/{y}.png" }.toTypedArray(),
    copyright = "© OpenStreetMap contributors © CARTO"
)

private val cartoDark: ITileSource = carto("dark_all", "CartoDarkMatter")
private val cartoVoyager: ITileSource = carto("rastertiles/voyager", "CartoVoyager")
private val esriImagery: ITileSource = TemplateTileSource(
    name = "EsriWorldImagery",
    maxZoom = 19,
    templates = arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"),
    copyright = "Imagery © Esri, Maxar, Earthstar Geographics"
)

enum class MapStyle(val label: String) {
    DARK("Dark"),
    STREETS("Streets"),
    SATELLITE("Satellite"),
    TOPO("Topographic"),
    OSM("OpenStreetMap");

    fun tileSource(): ITileSource = when (this) {
        DARK -> cartoDark
        STREETS -> cartoVoyager
        SATELLITE -> esriImagery
        TOPO -> TileSourceFactory.OpenTopo
        OSM -> TileSourceFactory.MAPNIK
    }

    companion object {
        fun fromName(name: String?, darkTheme: Boolean): MapStyle =
            values().firstOrNull { it.name == name } ?: if (darkTheme) DARK else STREETS
    }
}
