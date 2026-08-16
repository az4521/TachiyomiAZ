package eu.kanade.tachiyomi.ui.extension.repos

import android.os.Bundle
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.extension.api.ExtensionGithubApi
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Presenter of [RepoController]. Used to manage the repos for the extensions.
 */
class RepoPresenter(
    private val preferences: PreferencesHelper = Injekt.get()
) : BasePresenter<RepoController>() {
    val scope = CoroutineScope(Job() + Dispatchers.Main)

    private val api = ExtensionGithubApi()

    /**
     * List containing repos.
     */
    private var repos: List<String> = emptyList()

    private var reposJob: Job? = null

    /**
     * Called when the presenter is created.
     *
     * @param savedState The saved state of this presenter.
     */
    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        preferences.extensionRepos().asFlow().onEach { repos ->
            this.repos = repos.toList().sortedBy { it.lowercase() }

            val items = this.repos.map(::RepoItem)
            reposJob?.cancel()
            reposJob = flowOf(items).collectLatestCache(onNext = { view, list -> view.setRepos(list) })
        }.launchIn(scope)
    }

    /**
     * Creates and adds a new repo to the database.
     *
     * @param name The name of the repo to create.
     */
    fun createRepo(name: String) {
        // Do not allow duplicate repos.
        if (repoExists(name)) {
            deliverToView { it.onRepoExistsError() }
            return
        }

        // Do not allow invalid formats
        if (!name.matches(repoRegex) && !name.matches(urlRegex)) {
            deliverToView { it.onRepoInvalidNameError() }
            return
        }

        // A `username/repo` shorthand resolves to a known index URL, so there's nothing to check.
        if (!name.matches(urlRegex)) {
            addRepo(name)
            return
        }

        // The index file behind a URL can be named anything, so the only way to tell a repo index
        // from, say, the repo's web page is to ask the server what it is.
        scope.launch {
            // Reading the response body off the socket blocks, so keep it off the main thread.
            if (withContext(Dispatchers.IO) { api.isRepoIndexUrl(name) }) {
                addRepo(name)
            } else {
                deliverToView { it.onRepoInvalidUrlError() }
            }
        }
    }

    private fun addRepo(name: String) {
        preferences.extensionRepos().set((repos + name).toSet())
    }

    /**
     * Deletes the given repos from the database.
     *
     * @param repos The list of repos to delete.
     */
    fun deleteRepos(repos: List<String>) {
        preferences.extensionRepos().set(
            this.repos.filterNot { it in repos }.toSet()
        )
    }

    /**
     * Returns true if a repo with the given name already exists.
     */
    private fun repoExists(name: String): Boolean {
        return repos.any { it.equals(name, true) }
    }

    companion object {
        val repoRegex =
            """^[a-zA-Z-_.]*?\/[a-zA-Z-_.]*?$""".toRegex()

        // Accepts a full URL to any repo index. The file can be named anything — index.min.json,
        // repo.json, index.pb, or something else entirely — so the name isn't checked here; the
        // format is probed when the repo is added and detected again from the response at fetch
        // time.
        val urlRegex =
            """^https?://[^\s/]+(/\S*)?$""".toRegex()
    }
}
