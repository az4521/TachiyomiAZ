package eu.kanade.tachiyomi.data.updater.github

import eu.kanade.tachiyomi.network.NetworkHelper
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Used to connect with the GitHub API.
 */
interface GithubService {
    companion object {
        fun create(): GithubService {
            val restAdapter =
                Retrofit.Builder()
                    .baseUrl("https://api.github.com")
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(Injekt.get<NetworkHelper>().client)
                    .build()

            return restAdapter.create(GithubService::class.java)
        }
    }

    @GET("/repos/az4521/tachiyomiAZ/releases/latest")
    suspend fun getLatestVersion(): GithubRelease
}
