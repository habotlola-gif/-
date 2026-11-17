package com.example.shadetiles

import android.content.Context
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Одна настройка Settings.Secure, которая включается/выключается значением 1/0. */
fun Context.hasSecurePermission() =
    checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

abstract class SecureToggleTile(private val key: String) : TileService() {

    /** Доп. действия при смене состояния (например, выбор режима дальтонизма). */
    protected open fun onChanged(enabled: Boolean) {}

    private fun isOn() = Settings.Secure.getInt(contentResolver, key, 0) == 1

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (!hasSecurePermission()) {
            Toast.makeText(this, "Нет разрешения. Откройте приложение — там команда adb", Toast.LENGTH_LONG).show()
            return
        }
        val new = !isOn()
        Settings.Secure.putInt(contentResolver, key, if (new) 1 else 0)
        onChanged(new)
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (isOn()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
