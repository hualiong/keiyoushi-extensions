package eu.kanade.tachiyomi.extension.zh.bilinovel

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

class NovelInterceptor : Interceptor {

    companion object {
        val PREV_URL_REGEX = Regex("url_previous:'(.*?)'")
        val NEXT_URL_REGEX = Regex("url_next:'(.*?)'")
        val CHAPTER_ID_REGEX = Regex("/novel/(\\d+)/(\\d+)\\.html")
    }

    private fun regexOf(str: String?) = when (str) {
        "prev" -> PREV_URL_REGEX
        "next" -> NEXT_URL_REGEX
        else -> null
    }

    private fun predictUrlByContext(url: HttpUrl) = when (url.fragment) {
        "prev" -> {
            val groups = CHAPTER_ID_REGEX.find(url.toString())?.groups
            "/novel/${groups?.get(1)?.value}/${groups?.get(2)?.value?.toInt()?.plus(1)}.html"
        }

        "next" -> {
            val groups = CHAPTER_ID_REGEX.find(url.toString())?.groups
            "/novel/${groups?.get(1)?.value}/${groups?.get(2)?.value?.toInt()?.minus(1)}.html"
        }

        else -> "/novel/0/0.html"
    }

    private fun checkPC(response: Response) {
        if (response.isRedirect && response.header("Location")?.indexOf("linovelib") != -1) {
            throw Exception("不支持电脑端查看，请在“更多-设置-高级”中更换手机端UA标识")
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val origin = chain.request()
        regexOf(origin.url.fragment)?.let {
            val response =
                chain.proceed(origin.newBuilder().removeHeader("Accept-Encoding").build())
            val url = it.find(response.body.string())?.groups?.get(1)?.value
                ?: predictUrlByContext(origin.url)
            return response.newBuilder().code(302)
                .header("Location", url.replace(".", "_2.")).build()
        }
        return chain.proceed(origin.newBuilder().addHeader("Cookie", "night=1").build())
            .also(::checkPC)
    }
}
