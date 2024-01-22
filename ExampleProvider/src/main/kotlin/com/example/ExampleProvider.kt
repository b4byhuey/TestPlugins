package com.example

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.getImageAttr
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

open class ExampleProvider : MainAPI() {
    override var mainUrl = "https://anichin.icu"
    override var name = "AniChin"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val hasChromecastSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
	private val interceptor = CloudflareKiller()

    companion object {
        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
        fun getType(t: String?): TvType {
            return when {
                t?.contains("Movie", true) == true -> TvType.AnimeMovie
                t?.contains("Anime", true) == true -> TvType.Anime
                else -> TvType.OVA
            }
        }
    }

    override val mainPage = mainPageOf(
        "&status=&type=&order=update" to "Rilisan Terbaru",
        "&status=&type=movie&order=update" to "Movie",
        "&status=ongoing&sub=&order=update" to "Series Ongoing",
        "&status=completed&sub=&order=update" to "Series Completed",
        "&status=hiatus&sub=&order=update" to "Series Drop/Hiatus"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/anime/?page=$page${request.data}", interceptor = interceptor).document
        val home = document.select("article[itemscope=itemscope]").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperDramaLink(uri: String): String {
        return if (uri.contains("-episode-")) {
            "$mainUrl/anime/" + Regex("$mainUrl/(.+)-ep.+").find(uri)?.groupValues?.get(1)
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = getProperDramaLink(this.selectFirst("a.tip")!!.attr("href"))
        val title = this.selectFirst("h2[itemprop=headline]")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst(".limit > img")?.getImageAttr()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            posterHeaders = interceptor.getCookieHeaders(url).toMap()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link, interceptor = interceptor).document

        return document.select("article[itemscope=itemscope]").map {
            val title = it.selectFirst("h2[itemprop=headline]")!!.text().trim()
            val poster = it.selectFirst(".limit > img")!!.getImageAttr()
            val href = it.selectFirst("a.tip")!!.attr("href")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                posterHeaders = interceptor.getCookieHeaders(url).toMap()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("h1.entry-title")!!.text().trim()
        val poster = document.selectFirst(".thumb > img")?.getImageAttr()
        val tags = document.select(".genxed > a").map { it.text() }
        val type = document.selectFirst(".info-content .spe span:contains(Tipe:)")?.ownText()
        val year = Regex("\\d, ([0-9]*)").find(
            document.selectFirst(".info-content > .spe > span.split")!!.text().trim()
        )?.groupValues?.get(1).toString().toIntOrNull()
        val status = getStatus(
            document.select(".info-content > .spe > span:nth-child(1)")
                .text().trim().replace("Status: ", "")
        )
        val description = document.select(".entry-content > p").text().trim()
        val episodes = document.select(".eplister > ul > li").mapNotNull {
            val name = it.selectFirst("a > .epl-title")!!.text().trim().replace(" Subtitle Indonesia", "")
            val link = fixUrl(it.select("a").attr("href") ?: return@mapNotNull null)
            val epNum = Regex("([0-9]*)").find(it.selectFirst("a > .epl-num")!!
                .text().trim())?.groupValues?.get(1).toString().toIntOrNull()
            newEpisode(link) {
                this.name = name
                this.episode = epNum
            }

        }.reversed()

        val recommendations =
            document.select(".listupd > article[itemscope=itemscope]").map {
                val epTitle = it.selectFirst("h2[itemprop=headline]")!!.text().trim()
                val epPoster = it.selectFirst(".limit > img")!!.getImageAttr()
                val epHref = fixUrl(it.selectFirst("a.tip")!!.attr("href"))

                newAnimeSearchResponse(epTitle, epHref, TvType.Anime) {
                    this.posterUrl = epPoster
                    posterHeaders = interceptor.getCookieHeaders(url).toMap()
                }
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            getType(type),
            episodes = episodes
        ) {
            posterUrl = poster
            this.year = year
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            posterHeaders = interceptor.getCookieHeaders(url).toMap()
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean { 
        val document = app.get(data, interceptor = interceptor).document
        //val sources = document.select(".mobius > .mirror > option").mapNotNull {
        //    fixUrl(Jsoup.parse(base64Decode(it.attr("value"))).select("iframe").attr("src")
		//	.replace("https://rubystream.xyz", "https://stmruby.com"))
        //}
		
        //sources.map {
        //    loadExtractor(it, "https://anichin.top/", subtitleCallback, callback)
        //}
        //return true
    //}
		argamap(
            {
                document.select(".mobius > .mirror > option").apmap {
                    val dataPost = it.attr("data-post")
                    val dataNume = it.attr("data-nume")
                    val dataType = it.attr("data-type")
					val dataValue = fixUrl(Jsoup.parse(base64Decode(it.attr("value"))))

                    val iframe = app.post(
                        url = "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "player_ajax",
                            "post" to dataPost,
                            "nume" to dataNume,
                            "type" to dataType,
							"value" to dataValue
                        ),
                        referer = data,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).document.select("iframe").attr("src")

                    loadFixedExtractor((iframe), it.text(), "$mainUrl/", subtitleCallback, callback)

                }
            }            
        )

        return true
    }
	
	fun Element.getImageAttr(): String? {
    return when {
        this.hasAttr("data-src") -> this.attr("abs:data-src")
        this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
        this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
		this.hasAttr("content") -> this.attr("abs:content")
		this.hasAttr("data-setbg") -> this.attr("data-setbg")
		this.hasAttr("data-original") -> this.attr("data-original")
		this.hasAttr("style") -> this.attr("style").substringAfter("url(").substringBeforeLast(")")
        else -> this.attr("abs:src")
		}
	}
	
	private suspend fun loadFixedExtractor(
        url: String,
        name: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            callback.invoke(
                ExtractorLink(
                    link.name,
                    link.name,
                    link.url,
                    link.referer,
                    quality,
                    link.type,
                    link.headers,
                    link.extractorData
                )
            )
        }
    }
}
