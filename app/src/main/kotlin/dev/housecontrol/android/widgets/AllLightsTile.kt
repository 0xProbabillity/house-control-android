package dev.housecontrol.android.widgets

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.housecontrol.android.core.HomeAction

/** A Quick Settings tile: every light of the home on or off, from anywhere. */
class AllLightsTile : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        val snapshot = WidgetStore(this).snapshot() ?: return
        WidgetBridge.perform(this, HomeAction(HomeAction.ALL_LIGHTS, on = snapshot.lightsOn == 0))
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val snapshot = WidgetStore(this).snapshot()
        tile.state = when {
            snapshot == null || snapshot.lights.isEmpty() -> Tile.STATE_UNAVAILABLE
            snapshot.lightsOn > 0 -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = snapshot?.let(::statusLine)
        tile.updateTile()
    }
}
