package com.lilit.koleksiyon

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LilitKoleksiyonPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AniziumProvider())
        registerMainAPI(DiziPalProvider())
        registerMainAPI(DiziYouProvider())
        registerMainAPI(FullHDFilmizleseneProvider())
        registerMainAPI(HDFilmCehennemiProvider())
    }
}
