package com.example.fluxgalatic

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class SetupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            fitsSystemWindows = true
            setPadding(pad, pad, pad, pad)
        }

        val info = TextView(this).apply {
            text = getString(R.string.setup_info)
            textSize = 16f
        }

        val btnLocation = Button(this).apply {
            text = getString(R.string.setup_location)
            setOnClickListener {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            }
        }

        val btnWallpaper = Button(this).apply {
            text = getString(R.string.setup_set_wallpaper)
            setOnClickListener {
                try {
                    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        ComponentName(this@SetupActivity, GalaxyArrowWallpaperService::class.java)
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                }
            }
        }

        layout.addView(info)
        layout.addView(btnLocation)
        layout.addView(btnWallpaper)
        setContentView(layout)
    }
}