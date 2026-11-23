package com.lilit.koleksiyon

import android.util.Base64
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Random

/**
 * HDFilmCehennemi — erişilebilir alan adı hdfilmcehennemi.land.
 * Eski .nl alan adı Cloudflare/451 nedeniyle kutuda açılmıyordu; bu sürüm
 * WordPress AJAX + SetPlay/FastPlay zincirini doğrudan çözer.
 */
class HDFilmCehennemiProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.land"
    override var name = "HDFilmCehennemi"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val siteHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "accept-language" to "tr-TR,tr;q=0.9",
    )

    override val mainPage get() = mainPageOf(
        mainUrl to "Ana Sayfa",
        "$mainUrl/film/" to "Filmler",
        "$mainUrl/dizi/" to "Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val doc = app.get(url, headers = siteHeaders).document
        val items = doc.select("article.item").mapNotNull { it.toCard() }
            .ifEmpty { doc.select("div.result-item article").mapNotNull { it.toCard() } }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toCard(): SearchResponse? {
        val href = fixUrlNull(selectFirst("a[href*='/film/'], a[href*='/dizi/']")?.attr("href"))
            ?: selectFirst("div.poster a, div.image a")?.attr("href")?.let { fixUrlNull(it) }
            ?: return null
        val title = selectFirst("div.details div.title, div.title, h2.flbaslik, h2.title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.removeSuffix(" Poster")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = selectFirst("img")?.let { img ->
            fixUrlNull(img.attr("data-src").ifBlank { img.attr("src") })
        }
        val isSeries = href.contains("/dizi/")
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/?s=$q", headers = siteHeaders).document
        return doc.select("div.result-item article, article.item").mapNotNull { it.toCard() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = siteHeaders).document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("$name: başlık okunamadı.")
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        if (url.contains("/dizi/")) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = app.get(data, headers = siteHeaders).document
        val nonce = Regex("""nonce\s*:\s*['"]([^'"]+)['"]""").find(doc.html())?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: oynatıcı anahtarı alınamadı.")
        val postId = doc.selectFirst("[data-post-id]")?.attr("data-post-id")
            ?: throw ErrorLoadingException("$name: içerik kimliği alınamadı.")
        val player = doc.selectFirst("[data-player-name]")?.attr("data-player-name")
            ?.takeIf { it.isNotBlank() } ?: "SetPlay"

        val ajax = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf(
                "action" to "get_video_url",
                "nonce" to nonce,
                "post_id" to postId,
                "player_name" to player,
                "part_key" to "",
            ),
            headers = siteHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = data,
        ).text
        val iframeUrl = Regex(""""url"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .find(ajax)?.groupValues?.get(1)
            ?.replace("\\/", "/")?.replace("\\\"", "\"")
            ?.takeIf { it.startsWith("http") }
            ?: throw ErrorLoadingException("$name: oynatıcı adresi alınamadı.")

        val playerHtml = app.get(iframeUrl, headers = siteHeaders, referer = data).text
        val frame = Regex("""SPG\.cerceve\("[^"]*","([^"]+)","([^"]+)"\)""")
            .find(playerHtml) ?: throw ErrorLoadingException("$name: akış çözülemedi.")
        val enc = Base64.decode(frame.groupValues[1], Base64.DEFAULT)
        val key = Base64.decode(frame.groupValues[2], Base64.DEFAULT)
        if (enc.isEmpty() || key.isEmpty()) throw ErrorLoadingException("$name: akış çözülemedi.")
        val fastplayUrl = String(CharArray(enc.size) { i -> (enc[i].toInt() xor key[i % key.size].toInt()).toChar() })
        if (!fastplayUrl.startsWith("http")) throw ErrorLoadingException("$name: akış çözülemedi.")

        val fp = app.get(fastplayUrl, headers = siteHeaders, referer = iframeUrl).text
        val streamPath = Regex("""stream\s*:\s*"([^"]+)"""").find(fp)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("$name: manifest bulunamadı.")
        val sp = Regex(""""sp"\s*:\s*"([^"]+)"""").find(fp)?.groupValues?.get(1) ?: ""
        val spT = Regex(""""spT"\s*:\s*(\d+)""").find(fp)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

        callback(
            newExtractorLink(name, name, "https://fastplay.mom$streamPath", ExtractorLinkType.M3U8) {
                this.referer = fastplayUrl
                this.headers = siteHeaders + mapOf("X-Sp" to xSp(sp, spT), "Referer" to fastplayUrl)
            }
        )
        return true
    }

    /** Site JavaScript'teki FNV-1a imzası: sp|spT|base36(rastgele). */
    private fun xSp(sp: String, spT: Long): String {
        val r = java.lang.Long.toString((2176782336.0 * Random().nextDouble()).toLong(), 36)
        var hash = -2128831035
        for (b in "$sp|$spT|$r".toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toInt() and 0xFF)
            hash *= 16777619
        }
        return "$spT.$r.${java.lang.Integer.toUnsignedString(hash)}"
    }
}
