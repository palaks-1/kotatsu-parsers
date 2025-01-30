package org.koitharu.kotatsu.parsers.site.vi

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.PagedMangaParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("TRUYENTRANH3Q", "TruyenTranh3Q", "vi")
internal class TruyenTranh3Q(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.TRUYENTRANH3Q, 42) {

	override val configKeyDomain = ConfigKey.Domain("truyentranh3q.com")

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(
			SortOrder.UPDATED,
			SortOrder.NEWEST,
			SortOrder.POPULARITY,
			SortOrder.RATING,
		)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.OTHER,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/tim-kiem-nang-cao")
			
			append("?")
			
			if (page > 1) {
				append("page=")
				append(page)
				append("&")
			}

			if (!filter.query.isNullOrEmpty()) {
				append("keyword=")
				append(filter.query.urlEncoded())
				append("&")
			}

			append("sort=")
			append(
				when (order) {
					SortOrder.UPDATED -> "0"
					SortOrder.NEWEST -> "1"
					SortOrder.POPULARITY -> "2"
					SortOrder.RATING -> "6"
					else -> "0"
				}
			)

			append("&status=")
			if (filter.states.isNotEmpty()) {
				filter.states.oneOrThrowIfMany()?.let {
					append(
						when (it) {
							MangaState.ONGOING -> "1"
							MangaState.FINISHED -> "2"
							else -> "0"
						}
					)
				}
			} else {
				append("0")
			}

			append("&country=")
			if (filter.types.isNotEmpty()) {
				filter.types.oneOrThrowIfMany()?.let {
					append(
						when (it) {
							ContentType.MANGA -> "manga"
							ContentType.MANHWA -> "manhwa"
							ContentType.MANHUA -> "manhua"
							ContentType.OTHER -> "other"
							else -> "all"
						}
					)
				}
			} else append("all")

			if (filter.tags.isNotEmpty()) {
				append("&categories=")
				append(filter.tags.joinToString(",") { it.key })
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		// Detect NSFW by Manga Tags (Still in progress, not completed...)
		val nsfwTags = setOf("18+", "Adult", "Ecchi", "16+", "NTR", "Smut")
		
		return doc.select("ul.list_grid.grid > li").map { element ->
			val aTag = element.selectFirstOrThrow("h3 a")
			val tags = element.select(".genre-item").map {
				MangaTag(
					key = it.attr("href").substringAfterLast('-').substringBeforeLast('.'),
					title = it.text(),
					source = source
				)
			}.toSet()
			
			Manga(
				id = generateUid(aTag.attr("href")),
				title = aTag.text(),
				altTitle = null,
				url = aTag.attrAsRelativeUrl("href"),
				publicUrl = aTag.attr("href").toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				isNsfw = tags.any { it.title in nsfwTags }, // Scan tags title with nsfwTags (Failed)
				coverUrl = element.selectFirst(".book_avatar a img")?.src().orEmpty(),
				tags = tags,
				state = null,
				author = null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val tags = doc.select("ul.list01 li").mapToSet {
			MangaTag(
				key = it.attr("href").substringAfterLast('-').substringBeforeLast('.'),
				title = it.text(),
				source = source,
			)
		}

		return manga.copy(
			altTitle = doc.selectFirst("h2.other-name")?.textOrNull(),
			author = doc.select("li.author a").text(),
			tags = tags,
			description = doc.select("div.story-detail-info").text(),
			state = when (doc.selectFirst(".status p.col-xs-9")?.text()) {
				"Đang Cập Nhật" -> MangaState.ONGOING
				"Hoàn Thành" -> MangaState.FINISHED
				else -> null
			},
			chapters = doc.select("div.list_chapter div.works-chapter-item").mapChapters(reversed = true) { i, div ->
				val a = div.selectFirstOrThrow("a")
				val href = a.attrAsRelativeUrl("href")
				val name = a.text()
				val dateText = div.selectFirst(".time-chap")?.text()
				MangaChapter(
					id = generateUid(href),
					name = name,
					number = i + 1f,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = parseChapterDate(dateText),
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		return doc.select(".chapter_content img").map { img ->
			val url = img.requireSrc()
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseChapterDate(dateText: String?): Long {
		if (dateText == null) return 0

		val relativeTimePattern = Regex("(\\d+)\\s*(phút|giờ|ngày|tuần) trước")
		val absoluteTimePattern = Regex("(\\d{2}-\\d{2}-\\d{4})")

		return when {
			dateText.contains("phút trước") -> {
				val match = relativeTimePattern.find(dateText)
				val minutes = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
				System.currentTimeMillis() - minutes * 60 * 1000
			}

			dateText.contains("giờ trước") -> {
				val match = relativeTimePattern.find(dateText)
				val hours = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
				System.currentTimeMillis() - hours * 3600 * 1000
			}

			dateText.contains("ngày trước") -> {
				val match = relativeTimePattern.find(dateText)
				val days = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
				System.currentTimeMillis() - days * 86400 * 1000
			}

			dateText.contains("tuần trước") -> {
				val match = relativeTimePattern.find(dateText)
				val weeks = match?.groups?.get(1)?.value?.toIntOrNull() ?: 0
				System.currentTimeMillis() - weeks * 7 * 86400 * 1000
			}

			absoluteTimePattern.matches(dateText) -> {
				val formatter = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
				try {
					val parsedDate = formatter.parse(dateText)
					parsedDate?.time ?: 0L
				} catch (e: Exception) {
					0L
				}
			}

			else -> 0L
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/tim-kiem-nang-cao").parseHtml()
		val elements = doc.select(".genre-item")
		return elements.mapIndexed { index, element ->
			MangaTag(
				key = (index + 1).toString(),
				title = element.text(),
				source = source
			)
		}.toSet()
	}
}
