package eu.kanade.tachiyomi.network

import android.content.Context
import exh.log.maybeInjectEHLogger
import java.io.File
import okhttp3.Cache
import okhttp3.OkHttpClient

open class NetworkHelper(context: Context) {

    private val cacheDir = File(context.cacheDir, "network_cache")

    private val cacheSize = 5L * 1024 * 1024 // 5 MiB

    open val cookieManager = AndroidCookieJar()

    open val client = OkHttpClient.Builder()
            .cookieJar(cookieManager)
            .cache(Cache(cacheDir, cacheSize))
            .maybeInjectEHLogger()
            .build()

    open val cloudflareClient = client.newBuilder()
            .addInterceptor(UserAgentInterceptor())
            .addInterceptor(CloudflareInterceptor(context))
            .maybeInjectEHLogger()
            .build()
}
