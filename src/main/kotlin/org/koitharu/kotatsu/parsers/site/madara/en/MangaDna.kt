package org.koitharu.kotatsu.parsers.site.madara.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat

@MangaSourceParser("MANGADNA", "MangaDna", "en", ContentType.HENTAI)
internal class MangaDna(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MANGADNA, "mangadna.com", 20) {

	override val datePattern = "dd MMM yyyy"
	override val withoutAjax = true
	override val selectDesc = "div.dsct"
	override val selectChapter = "li.a-h"

	override suspend fun getFilterOptions() = super.getFilterOptions().copy(
		availableStates = emptySet(),
		availableContentRating = emptySet(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			when {
				!filter.query.isNullOrEmpty() -> {
					append("/search?q=")
					append(filter.query.urlEncoded())
					append("&page=")
					append(page.toString())
				}

				else -> {

					val tag = filter.tags.oneOrThrowIfMany()
					if (filter.tags.isNotEmpty()) {
						append("/$tagPrefix")
						append(tag?.key.orEmpty())
						append("/")
						append(page.toString())
					} else {
						append("/$listUrl")
						append("/page/")
						append(page.toString())
					}

					append("?orderby=")
					when (order) {
						SortOrder.POPULARITY -> append("trending")
						SortOrder.UPDATED -> append("latest")
						SortOrder.ALPHABETICAL -> append("alphabet")
						SortOrder.RATING -> append("rating")
						else -> append("latest")
					}
				}
			}
		}

		val doc = webClient.httpGet(url).parseHtml()


		return doc.select("div.home-item").map { div ->
			val href = div.selectFirst("a")?.attrAsRelativeUrlOrNull("href") ?: div.parseFailed("Link not found")
			val summary = div.selectFirst(".tab-summary") ?: div.selectFirst(".hcontent")
			val author = summary?.selectFirst(".mg_author")?.selectFirst("a")?.ownText()
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(div.host ?: domain),
				coverUrl = div.selectFirst("img")?.src(),
				title = (summary?.selectFirst("h3") ?: summary?.selectFirst("h4"))?.text().orEmpty(),
				altTitles = emptySet(),
				rating = div.selectFirst("div.hitem-rate span")?.ownText()?.toFloatOrNull()?.div(5f) ?: -1f,
				tags = summary?.selectFirst(".mg_genres")?.select("a")?.mapNotNullToSet { a ->
					MangaTag(
						key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
						title = a.text().ifEmpty { return@mapNotNullToSet null }.toTitleCase(),
						source = source,
					)
				}.orEmpty(),
				authors = setOfNotNull(author),
				state = when (summary?.selectFirst(".mg_status")?.selectFirst(".summary-content")?.ownText()
					?.lowercase().orEmpty()) {
					in ongoing -> MangaState.ONGOING
					in finished -> MangaState.FINISHED
					else -> null
				},
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val body = doc.body()

		val chaptersDeferred = async { getChapters(manga, doc) }

		val desc = body.select(selectDesc).html()

		val stateDiv = (body.selectFirst("div.post-content_item:contains(Status)"))?.selectLast("div.summary-content")

		val state = stateDiv?.let {
			when (it.text()) {
				in ongoing -> MangaState.ONGOING
				in finished -> MangaState.FINISHED
				else -> null
			}
		}

		val alt =
			doc.body().select(".post-content_item:contains(Alt) .summary-content").firstOrNull()?.tableValue()
				?.textOrNull()
				?: doc.body().select(".post-content_item:contains(Nomes alternativos: ) .summary-content")
					.firstOrNull()?.tableValue()?.textOrNull()

		manga.copy(
			tags = doc.body().select(selectGenre).mapToSet { a ->
				MangaTag(
					key = a.attr("href").removeSuffix('/').substringAfterLast('/'),
					title = a.text().toTitleCase(),
					source = source,
				)
			},
			description = desc,
			altTitles = setOfNotNull(alt),
			state = state,
			chapters = chaptersDeferred.await(),
		)
	}

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		val root2 = doc.body().selectFirstOrThrow("div.panel-manga-chapter")
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		return root2.select(selectChapter).mapChapters(reversed = true) { i, li ->
			val a = li.selectFirst("a")
			val href = a?.attrAsRelativeUrlOrNull("href") ?: li.parseFailed("Link is missing")
			val link = href + stylePage
			val dateText = li.selectFirst("a.c-new-tag")?.attr("title") ?: li.selectFirst(selectDate)?.text()
			val name = a.selectFirst("p")?.text() ?: a.ownText()
			MangaChapter(
				id = generateUid(href),
				title = name,
				number = i + 1f,
				volume = 0,
				url = link,
				uploadDate = parseChapterDate(
					dateFormat,
					dateText,
				),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		val root = doc.body().selectFirstOrThrow("div.read-manga").selectFirstOrThrow("div.read-content")
		return root.select("img").map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}
}
