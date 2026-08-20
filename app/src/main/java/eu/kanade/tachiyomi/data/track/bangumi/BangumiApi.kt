package eu.kanade.tachiyomi.data.track.bangumi

import android.net.Uri
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class BangumiApi(private val client: OkHttpClient, interceptor: BangumiInterceptor) {
    private val json: Json by injectLazy()
    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    suspend fun addLibManga(track: Track): Track =
        withIOContext {
            val body =
                FormBody.Builder()
                    .add("rating", track.score.toInt().toString())
                    .add("status", track.toBangumiStatus())
                    .build()
            val request =
                Request.Builder()
                    .url("$apiUrl/collection/${track.media_id}/update")
                    .post(body)
                    .build()
            authClient.newCall(request).awaitSuccess().close()
            track
        }

    suspend fun updateLibManga(track: Track): Track =
        withIOContext {
            // chapter update
            val body =
                FormBody.Builder()
                    .add("watched_eps", track.last_chapter_read.toString())
                    .build()
            val request =
                Request.Builder()
                    .url("$apiUrl/subject/${track.media_id}/update/watched_eps")
                    .post(body)
                    .build()

            // read status update
            val sbody =
                FormBody.Builder()
                    .add("status", track.toBangumiStatus())
                    .build()
            val srequest =
                Request.Builder()
                    .url("$apiUrl/collection/${track.media_id}/update")
                    .post(sbody)
                    .build()

            // Ordering matters: the status update ran first under the previous
            // flatMap chain, and the chapter update only ran if it succeeded.
            authClient.newCall(srequest).awaitSuccess().close()
            authClient.newCall(request).awaitSuccess().close()
            track
        }

    suspend fun search(search: String): List<TrackSearch> =
        withIOContext {
            val url =
                Uri.parse(
                    "$apiUrl/search/subject/${URLEncoder.encode(search, Charsets.UTF_8.name())}"
                ).buildUpon()
                    .appendQueryParameter("max_results", "20")
                    .build()
            val request =
                Request.Builder()
                    .url(url.toString())
                    .get()
                    .build()
            var responseBody =
                authClient.newCall(request).awaitSuccess().use { it.body.string() }
            if (responseBody.isEmpty()) {
                throw Exception("Null Response")
            }
            if (responseBody.contains("\"code\":404")) {
                responseBody = "{\"results\":0,\"list\":[]}"
            }
            val response = Json.parseToJsonElement(responseBody).jsonObject["list"]?.jsonArray
            response
                ?.filter { it.jsonObject["type"]!!.jsonPrimitive.int == 1 }
                ?.map { jsonToSearch(it.jsonObject) }
                .orEmpty()
        }

    private fun jsonToSearch(obj: JsonObject): TrackSearch {
        return TrackSearch.create(TrackManager.BANGUMI).apply {
            media_id = obj["id"]!!.jsonPrimitive.long
            title = obj["name_cn"]!!.jsonPrimitive.content
            cover_url = obj["images"]!!.jsonObject["common"]!!.jsonPrimitive.content
            summary = obj["name"]!!.jsonPrimitive.content
            tracking_url = obj["url"]!!.jsonPrimitive.content
        }
    }

    private fun jsonToTrack(mangas: JsonObject): Track {
        return Track.create(TrackManager.BANGUMI).apply {
            title = mangas["name"]!!.jsonPrimitive.content
            media_id = mangas["id"]!!.jsonPrimitive.long
            score =
                mangas["rating"]?.let { rating ->
                    if (rating !is JsonNull && rating is JsonObject) {
                        rating["score"]!!.jsonPrimitive.float
                    } else {
                        0f
                    }
                } ?: 0f
            status = Bangumi.DEFAULT_STATUS
            tracking_url = mangas["url"]!!.jsonPrimitive.content
        }
    }

    suspend fun findLibManga(track: Track): Track? =
        withIOContext {
            val urlMangas = "$apiUrl/subject/${track.media_id}"
            val requestMangas =
                Request.Builder()
                    .url(urlMangas)
                    .get()
                    .build()

            // get comic info
            val responseBody =
                authClient.newCall(requestMangas).awaitSuccess().use { it.body.string() }
            jsonToTrack(Json.parseToJsonElement(responseBody).jsonObject)
        }

    suspend fun statusLibManga(track: Track): Track? =
        withIOContext {
            val urlUserRead = "$apiUrl/collection/${track.media_id}"
            val requestUserRead =
                Request.Builder()
                    .url(urlUserRead)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .get()
                    .build()

            // todo get user readed chapter here
            val resp =
                authClient.newCall(requestUserRead).awaitSuccess().use { it.body.string() }
            val coll = json.decodeFromString<Collection>(resp)
            track.status = coll.status?.id!!
            track.last_chapter_read = coll.ep_status!!
            track
        }

    suspend fun accessToken(code: String): OAuth =
        withIOContext {
            val responseBody =
                client.newCall(accessTokenRequest(code)).awaitSuccess().use { it.body.string() }
            if (responseBody.isEmpty()) {
                throw Exception("Null Response")
            }
            json.decodeFromString<OAuth>(responseBody)
        }

    private fun accessTokenRequest(code: String) =
        POST(
            oauthUrl,
            body =
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("code", code)
                .add("redirect_uri", redirectUrl)
                .build()
        )

    companion object {
        private const val clientId = "bgm10555cda0762e80ca"
        private const val clientSecret = "8fff394a8627b4c388cbf349ec865775"

        private const val baseUrl = "https://bangumi.org"
        private const val apiUrl = "https://api.bgm.tv"
        private const val oauthUrl = "https://bgm.tv/oauth/access_token"
        private const val loginUrl = "https://bgm.tv/oauth/authorize"

        private const val redirectUrl = "tachiyomi://bangumi-auth"
        private const val baseMangaUrl = "$apiUrl/mangas"

        fun mangaUrl(remoteId: Int): String {
            return "$baseMangaUrl/$remoteId"
        }

        fun authUrl() =
            Uri.parse(loginUrl).buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", redirectUrl)
                .build()

        fun refreshTokenRequest(token: String) =
            POST(
                oauthUrl,
                body =
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("refresh_token", token)
                    .add("redirect_uri", redirectUrl)
                    .build()
            )
    }
}
