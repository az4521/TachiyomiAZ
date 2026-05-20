package eu.kanade.tachiyomi.data.track.kitsu

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.adapter.rxjava.RxJavaCallAdapterFactory
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import rx.Observable

class KitsuApi(private val client: OkHttpClient, interceptor: KitsuInterceptor) {
    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    private val json = Json { ignoreUnknownKeys = true }

    private val rest =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(authClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
            .build()
            .create(Rest::class.java)

    private val searchRest =
        Retrofit.Builder()
            .baseUrl(algoliaKeyUrl)
            .client(authClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
            .build()
            .create(SearchKeyRest::class.java)

    private val algoliaRest =
        Retrofit.Builder()
            .baseUrl(algoliaUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
            .build()
            .create(AgoliaSearchRest::class.java)

    fun addLibManga(
        track: Track,
        userId: String
    ): Observable<Track> {
        return Observable.defer {
            // @formatter:off
            val data = buildJsonObject {
                put("type", "libraryEntries")
                putJsonObject("attributes") {
                    put("status", track.toKitsuStatus())
                    put("progress", track.last_chapter_read)
                }
                putJsonObject("relationships") {
                    putJsonObject("user") {
                        putJsonObject("data") {
                            put("id", userId)
                            put("type", "users")
                        }
                    }
                    putJsonObject("media") {
                        putJsonObject("data") {
                            put("id", track.media_id)
                            put("type", "manga")
                        }
                    }
                }
            }

            rest.addLibManga(buildJsonObject { put("data", data) })
                .map { json ->
                    track.media_id = json["data"]!!.jsonObject["id"]!!.jsonPrimitive.int
                    track
                }
        }
    }

    fun updateLibManga(track: Track): Observable<Track> {
        return Observable.defer {
            // @formatter:off
            val data = buildJsonObject {
                put("type", "libraryEntries")
                put("id", track.media_id)
                putJsonObject("attributes") {
                    put("status", track.toKitsuStatus())
                    put("progress", track.last_chapter_read)
                    put("ratingTwenty", track.toKitsuScore())
                }
            }
            // @formatter:on

            rest.updateLibManga(track.media_id, buildJsonObject { put("data", data) })
                .map { track }
        }
    }

    fun search(query: String): Observable<List<TrackSearch>> {
        return searchRest
            .getKey().map { json ->
                json["media"]!!.jsonObject["key"]!!.jsonPrimitive.content
            }.flatMap { key ->
                algoliaSearch(key, query)
            }
    }

    private fun algoliaSearch(
        key: String,
        query: String
    ): Observable<List<TrackSearch>> {
        val jsonObject = buildJsonObject { put("params", "query=$query$algoliaFilter") }
        return algoliaRest
            .getSearchQuery(algoliaAppId, key, jsonObject)
            .map { json ->
                val data = json["hits"]!!.jsonArray
                data.map { KitsuSearchManga(it.jsonObject) }
                    .filter { it.subType != "novel" }
                    .map { it.toTrack() }
            }
    }

    fun findLibManga(
        track: Track,
        userId: String
    ): Observable<Track?> {
        return rest.findLibManga(track.media_id, userId)
            .map { json ->
                val data = json["data"]!!.jsonArray
                if (data.size > 0) {
                    val manga = json["included"]!!.jsonArray[0].jsonObject
                    KitsuLibManga(data[0].jsonObject, manga).toTrack()
                } else {
                    null
                }
            }
    }

    fun getLibManga(track: Track): Observable<Track> {
        return rest.getLibManga(track.media_id)
            .map { json ->
                val data = json["data"]!!.jsonArray
                if (data.size > 0) {
                    val manga = json["included"]!!.jsonArray[0].jsonObject
                    KitsuLibManga(data[0].jsonObject, manga).toTrack()
                } else {
                    throw Exception("Could not find manga")
                }
            }
    }

    fun login(
        username: String,
        password: String
    ): Observable<OAuth> {
        return Retrofit.Builder()
            .baseUrl(loginUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
            .build()
            .create(LoginRest::class.java)
            .requestAccessToken(username, password)
    }

    fun getCurrentUser(): Observable<String> {
        return rest.getCurrentUser().map { it["data"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content }
    }

    private interface Rest {
        @Headers("Content-Type: application/vnd.api+json")
        @POST("library-entries")
        fun addLibManga(
            @Body data: JsonObject
        ): Observable<JsonObject>

        @Headers("Content-Type: application/vnd.api+json")
        @PATCH("library-entries/{id}")
        fun updateLibManga(
            @Path("id") remoteId: Int,
            @Body data: JsonObject
        ): Observable<JsonObject>

        @GET("library-entries")
        fun findLibManga(
            @Query("filter[manga_id]", encoded = true) remoteId: Int,
            @Query("filter[user_id]", encoded = true) userId: String,
            @Query("include") includes: String = "manga"
        ): Observable<JsonObject>

        @GET("library-entries")
        fun getLibManga(
            @Query("filter[id]", encoded = true) remoteId: Int,
            @Query("include") includes: String = "manga"
        ): Observable<JsonObject>

        @GET("users")
        fun getCurrentUser(
            @Query("filter[self]", encoded = true) self: Boolean = true
        ): Observable<JsonObject>
    }

    private interface SearchKeyRest {
        @GET("media/")
        fun getKey(): Observable<JsonObject>
    }

    private interface AgoliaSearchRest {
        @POST("query/")
        fun getSearchQuery(
            @Header("X-Algolia-Application-Id") appid: String,
            @Header("X-Algolia-API-Key") key: String,
            @Body json: JsonObject
        ): Observable<JsonObject>
    }

    private interface LoginRest {
        @FormUrlEncoded
        @POST("oauth/token")
        fun requestAccessToken(
            @Field("username") username: String,
            @Field("password") password: String,
            @Field("grant_type") grantType: String = "password",
            @Field("client_id") client_id: String = clientId,
            @Field("client_secret") client_secret: String = clientSecret
        ): Observable<OAuth>
    }

    companion object {
        private const val clientId = "dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd"
        private const val clientSecret = "54d7307928f63414defd96399fc31ba847961ceaecef3a5fd93144e960c0e151"
        private const val baseUrl = "https://kitsu.io/api/edge/"
        private const val loginUrl = "https://kitsu.io/api/"
        private const val baseMangaUrl = "https://kitsu.io/manga/"
        private const val algoliaKeyUrl = "https://kitsu.io/api/edge/algolia-keys/"
        private const val algoliaUrl = "https://AWQO5J657S-dsn.algolia.net/1/indexes/production_media/"
        private const val algoliaAppId = "AWQO5J657S"
        private const val algoliaFilter = "&facetFilters=%5B%22kind%3Amanga%22%5D&attributesToRetrieve=%5B%22synopsis%22%2C%22canonicalTitle%22%2C%22chapterCount%22%2C%22posterImage%22%2C%22startDate%22%2C%22subtype%22%2C%22endDate%22%2C%20%22id%22%5D"

        fun mangaUrl(remoteId: Int): String {
            return baseMangaUrl + remoteId
        }

        fun refreshTokenRequest(token: String) =
            POST(
                "${loginUrl}oauth/token",
                body =
                FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("refresh_token", token)
                    .build()
            )
    }
}
