package com.example.shadetiles

import android.provider.Settings

class InversionTileService : SecureToggleTile("accessibility_display_inversion_enabled")

class GrayscaleTileService : SecureToggleTile("accessibility_display_daltonizer_enabled") {
    override fun onChanged(enabled: Boolean) {
        // 0 = монохромный режим
        Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer", 0)
    }
}

class ContrastTileService : SecureToggleTile("high_text_contrast_enabled")
