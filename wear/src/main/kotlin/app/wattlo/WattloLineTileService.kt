package app.wattlo

/** The second tile: a window of 12 hours as a line instead of the day ring (see [WattloTileService]). */
class WattloLineTileService : WattloTileService() {
    override val lineMode = true
}
