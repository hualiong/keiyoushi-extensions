package eu.kanade.tachiyomi.extension.zh.bilinovel

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.floor

class BiliNovel : HttpSource(), ConfigurableSource {
    override val baseUrl = "https://www.bilinovel.com"
    override val lang = "zh"
    override val name = "哔哩轻小说"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override val client = super.client.newBuilder()
        .addInterceptor(HtmlInterceptor(baseUrl, preferences))
        .rateLimit(10, 10).addNetworkInterceptor(NovelInterceptor()).build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept-Language", "zh")
        .add("Accept", "*/*")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        preferencesInternal(screen.context, preferences).forEach(screen::addPreference)
    }

    // Customize

    companion object {
        val DATE_REGEX = Regex("\\d{4}-\\d{1,2}-\\d{1,2}")
        val PAGE_REGEX = Regex("第(\\d+)/(\\d+)页")
        val MANGA_ID_REGEX = Regex("/novel/(\\d+)\\.html")
        val CHAPTER_ID_REGEX = Regex("/novel/\\d+/(\\d+)(?:_\\d+)?\\.html")
        val PAGE_SIZE_REGEX = Regex("（\\d+/(\\d+)）")
        val EXPRESSION_REGEX = Regex("Number.*?;")
        val SALT_REGEX = Regex("(?<![a-zA-Z0-9_])-?0x[0-9a-fA-F]+(?:[+*\\-]-?0x[0-9a-fA-F]+)+")
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.CHINESE)
    }

    private val salt by lazy {
        try {
            val list = mutableListOf<String>()
            val call = client.newCall(GET("$baseUrl/themes/zhmb/js/chapterlog.js", headers))
            EXPRESSION_REGEX.findAll(call.execute().body.string()).forEach { m ->
                SALT_REGEX.findAll(m.value).takeIf { it.count() == 2 }?.forEach { list.add(it.value) }
            }
            list.map(::calculate)
        } catch (_: Exception) {
            listOf(132, 234)
        }
    }
    private val SManga.id get() = MANGA_ID_REGEX.find(url)!!.groups[1]!!.value
    private fun String.toHalfWidthDigits(): String {
        return this.map { if (it in '０'..'９') it - 65248 else it }.joinToString("")
    }

    private fun calculate(expression: String): Int {
        var newExpression = expression.replace(" ", "")
        val hexPattern = Regex("(-?)0x([0-9a-fA-F]+)")
        newExpression = hexPattern.replace(newExpression) { matchResult ->
            val sign = matchResult.groupValues[1]
            val hexStr = matchResult.groupValues[2]
            var num = hexStr.toLong(16)
            if (sign == "-") {
                num = -num
            }
            num.toString()
        }
        val tokenRegex = Regex("-?\\d+|[+*/-]")
        val tokens = tokenRegex.findAll(newExpression).map { it.value }.toList()
        if (tokens.isEmpty()) {
            return 0
        }
        val intermediateTokens = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token == "*" || token == "/") {
                if (intermediateTokens.isEmpty()) {
                    throw IllegalArgumentException("Invalid expression: operator without left operand")
                }
                val left = intermediateTokens.removeAt(intermediateTokens.size - 1).toInt()
                if (index + 1 >= tokens.size) {
                    throw IllegalArgumentException("Invalid expression: operator without right operand")
                }
                val right = tokens[index + 1].toInt()
                if (token == "/" && right == 0) {
                    throw ArithmeticException("Division by zero")
                }
                val result = if (token == "*") left * right else left / right
                intermediateTokens.add(result.toString())
                index += 2
            } else {
                intermediateTokens.add(token)
                index++
            }
        }
        if (intermediateTokens.isEmpty()) {
            return 0
        }
        var result = intermediateTokens[0].toInt()
        index = 1
        while (index < intermediateTokens.size) {
            val op = intermediateTokens[index]
            if (op == "+" || op == "-") {
                if (index + 1 >= intermediateTokens.size) {
                    throw IllegalArgumentException("Invalid expression: operator without right operand")
                }
                val nextNum = intermediateTokens[index + 1].toInt()
                if (op == "+") {
                    result += nextNum
                } else {
                    result -= nextNum
                }
                index += 2
            } else {
                throw IllegalArgumentException("Invalid operator: $op")
            }
        }
        return result
    }

    private fun hasNextPage(doc: Document, size: Int): Boolean {
        val url = doc.location()
        return when {
            url.contains("wenku") -> {
                val total = doc.selectFirst("#pagelink > .last")!!.text().toInt()
                val cur = doc.selectFirst("#pagelink > strong")!!.text().toInt()
                cur < total
            }

            url.contains("search") -> {
                val find = PAGE_REGEX.find(doc.selectFirst("#pagelink > span")!!.text())!!
                find.groups[1]!!.value.toInt() < find.groups[1]!!.value.toInt()
            }

            else -> size == 50
        }
    }

    private fun getChapterUrlByContext(i: Int, els: Elements) = when (i) {
        0 -> "${els[1].attr("href")}#prev"
        else -> "${els[i - 1].attr("href")}#next"
    }

    private fun handleContent(content: Element, chapterId: Int): String {
        // 1. 计算种子
        val seed = chapterId * salt[0] + salt[1]

        // 2. 获取所有子节点（包括文本节点等）
        val childNodes = content.children().toMutableList().also {
            it.removeIf { e ->
                e.tagName() != "img" && (e.tagName() != "p" || e.text().trim().isBlank())
            }
            it.forEachIndexed { i, e ->
                if (e.tagName() == "img" && e.hasAttr("data-src")) {
                    it[i] = e.attr("src", e.attr("data-src"))
                }
            }
        }

        // 3. 过滤出有效的<p>元素节点
        val paragraphs = childNodes.filter { it.tagName() == "p" }.toMutableList()

        // 5. 创建排列数组
        val n = paragraphs.size
        val permutation = mutableListOf<Int>().apply {
            // 前20个保持原顺序
            addAll(0 until minOf(20, n))
            // 处理超过20的部分
            if (n > 20) {
                val after20 = (20 until n).toMutableList()
                var num = seed.toLong()
                for (i in after20.size - 1 downTo 1) {
                    num = (num * 9302L + 49397L) % 233280L
                    val j = floor((num / 233280.0) * (i + 1)).toInt()
                    after20[j] = after20[i].also { after20[i] = after20[j] }
                }
                addAll(after20)
            }
        }

        // 6. 创建重排序后的段落数组
        val shuffled = arrayOfNulls<Element>(n).apply {
            for (i in 0 until n) {
                this[permutation[i]] = paragraphs[i].also {
                    it.removeAttr("class")
                    it.text("\u00A0\u00A0\u00A0\u00A0" + it.text())
                }
            }
        }.map { it!! } // 转换为非空列表

        // 7. 替换原始节点中的<p>元素
        var paraIndex = 0
        childNodes.forEachIndexed { i, e ->
            if (e.tagName() == "p") {
                childNodes[i] = shuffled[paraIndex++]
            }
        }

        // 8. 清空并重新添加处理后的节点
        content.html("")
        content.appendChildren(childNodes)

        // 9. 返回最终HTML
        return content.html()
    }

    // Popular Page

    override fun popularMangaRequest(page: Int): Request {
        val suffix = preferences.getString(PREF_POPULAR_DISPLAY, "/top/weekvisit/%d.html")!!
        return GET(baseUrl + String.format(suffix, page), headers)
    }

    override fun popularMangaParse(response: Response) = response.asJsoup().let { doc ->
        val mangas = doc.select(".book-layout").map {
            SManga.create().apply {
                setUrlWithoutDomain(it.absUrl("href"))
                val img = it.selectFirst("img")!!
                thumbnail_url = img.absUrl("data-src")
                title = img.attr("alt")
            }
        }
        MangasPage(mangas, hasNextPage(doc, mangas.size))
    }

    // Latest Page

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/top/lastupdate/$page.html", headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // Search Page

    override fun getFilterList() = buildFilterList()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            url.addPathSegment("search").addPathSegment("${query}_$page.html")
        } else {
            url.addPathSegment("wenku")
                .addPathSegment("${filters[3]}_${filters[2]}_${filters[6]}_${filters[4]}_${filters[1]}_0_0_${filters[5]}_${page}_0.html")
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.pathSegments.contains("novel")) {
            return MangasPage(listOf(mangaDetailsParse(response)), false)
        }
        return popularMangaParse(response)
    }

    // Manga Detail Page

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val doc = response.asJsoup()
        val meta = doc.select(".book-meta")[1].text().split("|")
        val bkname =
            doc.selectFirst(".bkname-body")?.let { "**別名**：${it.text()}\n\n---\n\n" } ?: ""
        setUrlWithoutDomain(doc.location())
        title = doc.selectFirst(".book-title")!!.text()
        thumbnail_url = doc.selectFirst(".book-cover")!!.attr("src")
        description = bkname + doc.selectFirst("#bookSummary > content")?.wholeText()?.trim()
        author = doc.selectFirst(".authorname")?.text()
        status = when (meta.getOrNull(1)) {
            "连载" -> SManga.ONGOING
            "完结" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        genre =
            (doc.select(".tag-small").map(Element::text) + meta.getOrElse(2) { "" }).joinToString()
        initialized = true
    }

    // Catalog Page

    override fun chapterListRequest(manga: SManga) =
        GET("$baseUrl/novel/${manga.id}/catalog", headers)

    override fun chapterListParse(response: Response) = response.asJsoup().let {
        val info = it.selectFirst(".chapter-sub-title")!!.text()
        val date = DATE_FORMAT.tryParse(DATE_REGEX.find(info)?.value)
        it.select(".catalog-volume").flatMap { v ->
            val chapterBar = v.selectFirst(".chapter-bar")!!.text().toHalfWidthDigits()
            val chapters = v.select(".chapter-li-a")
            chapters.mapIndexed { i, e ->
                val url = e.absUrl("href").takeUnless("javascript:cid(1)"::equals)
                SChapter.create().apply {
                    name = e.text().toHalfWidthDigits()
                    date_upload = date
                    scanlator = chapterBar
                    setUrlWithoutDomain(url ?: getChapterUrlByContext(i, chapters))
                }
            }
        }.reversed()
    }

    // Manga View Page

    override fun pageListRequest(chapter: SChapter) =
        GET(baseUrl + chapter.url.replace(".", "_2."), headers)

    override fun pageListParse(response: Response) = response.asJsoup().let { doc ->
        doc.selectFirst("#acontent > .center-note")?.run { throw Exception(text()) }
        val size = PAGE_SIZE_REGEX.find(doc.selectFirst("#atitle")!!.text())!!.groups[1]!!.value
        val prefix = doc.location().substringBeforeLast("_")
        List(size.toInt()) { i ->
            Page(i, prefix + "${if (i > 0) "_${i + 1}" else ""}.html")
        }
    }

    // Image

    override fun imageUrlParse(response: Response) = response.asJsoup().let { doc ->
        val title = doc.selectFirst("#atitle")?.html()?.takeIf { it.indexOf("/") < 0 }
        val content = doc.selectFirst("#acontent")!!
        val chapterId = CHAPTER_ID_REGEX.find(doc.location())!!.groups[1]!!.value
        HtmlInterceptorHelper.createUrl(title ?: "", handleContent(content, chapterId.toInt()))
    }
}
