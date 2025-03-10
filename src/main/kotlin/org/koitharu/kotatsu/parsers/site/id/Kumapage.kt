package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.LegacyPagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("KUMAPAGE", "Kumapage", "id")
internal class Kumapage(context: MangaLoaderContext) :
	LegacyPagedMangaParser(context, MangaParserSource.KUMAPAGE, 14) {

	override val configKeyDomain: ConfigKey.Domain
        get() = ConfigKey.Domain("www.kumapage.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

    override val availableSortOrders: Set<SortOrder>
        get() = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags()
    )

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return when {
			!filter.query.isNullOrEmpty() -> {
				val genre = if (filter.tags.isNotEmpty()) filter.tags.first().key else "all"
				val url = "https://$domain/search-process/"
				val headers = Headers.Builder()
					.add("Content-Type", "application/x-www-form-urlencoded")
					.build()
				val response = webClient.httpPost(url.toHttpUrl(), payload = "view=1&keyword=${filter.query}&genre=$genre", headers).parseHtml()
				parseSearchList(response)
			}

			filter.tags.isNotEmpty() -> {
				val tags = filter.tags.joinToString("&genre[]=") { it.key }
				val url = "https://$domain/daftar-komik?page=$page&genre[]=$tags"
				val response = webClient.httpGet(url.toHttpUrl()).parseHtml()
				parseMangaList(response)
			}

			else -> {
				val url = "https://$domain/daftar-komik?page=$page"
				val response = webClient.httpGet(url.toHttpUrl()).parseHtml()
				parseMangaList(response)
			}
		}
	}

	private fun parseSearchList(doc: Document): List<Manga> {
		return doc.select("div.item").map { item ->
			val a = item.selectFirst("a.header")
			val img = item.selectFirst("div.image img")
            val url = a?.attr("href") ?: ""
            val title = a?.text() ?: ""
			Manga(
				id = generateUid(url),
				url = url,
				publicUrl = url,
				title = title,
				altTitles = emptySet(),
				authors = emptySet(),
				description = item.selectFirst("div.description p")?.text(),
				tags = emptySet(),
				rating = RATING_UNKNOWN,
				state = null,
				coverUrl = img?.attr("src"),
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				source = source,
			)
		}
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div#daftar-komik a.ui.link").map { item ->
			val title = item.selectFirst("p.nama-full")?.text() ?: ""
			val img = item.selectFirst("img")?.attr("src") ?: ""
			val url = item.attr("href") ?: ""

			Manga(
				id = generateUid(url),
				url = url,
				publicUrl = url,
				title = title,
				altTitles = emptySet(),
				authors = emptySet(),
				description = null,
				tags = emptySet(),
				rating = RATING_UNKNOWN,
				state = null,
				coverUrl = img,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				source = source,
			)
		}
	}

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val state = if (doc.selectFirst("div.detail-singular:has(p:contains(Status)) + p")
            ?.text() == "Active") { MangaState.ONGOING } else { MangaState.FINISHED }
        val description = doc.selectFirst("td:contains(Synopsis) + td")?.text()
        val tags = doc.select("div.categories p.category").map { tag ->
            MangaTag(title = tag.text(), key = "", source = source)
        }.toSet()

        val chapters = doc.select("tbody#result-comic tr").mapIndexed { i, row ->
            MangaChapter(
                id = generateUid(row.select("td:nth-child(4) a").attr("href")),
                title = row.select("td:nth-child(2)").text(),
                number = i + 1f,
                volume = 0,
                url = row.select("td:nth-child(4) a").attr("href"),
                scanlator = null,
                uploadDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").tryParse(row.select("td:nth-child(3)").text()),
                branch = null,
                source = source,
            )
        }

        return manga.copy(
            description = description,
            state = state,
            tags = tags,
            chapters = chapters,
        )
    }

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("div.content_place img").mapNotNull { img ->
			val url = img.attr("src")
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

    private suspend fun fetchTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/search").parseHtml()
		return doc.select("select#genre option").map { option ->
			val key = option.attr("value")
			val title = option.text()
            MangaTag(title = title, key = key, source = source)
		}.toSet()
	}
}