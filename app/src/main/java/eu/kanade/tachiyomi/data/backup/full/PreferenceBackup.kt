package eu.kanade.tachiyomi.data.backup.full

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import eu.kanade.tachiyomi.data.backup.full.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.full.models.BackupPreferencePolicy
import eu.kanade.tachiyomi.data.backup.full.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.full.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.full.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.full.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.full.models.PreferenceValue
import eu.kanade.tachiyomi.data.backup.full.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.full.models.StringSetPreferenceValue
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Reads the app's settings into a backup and puts them back.
 *
 * SharedPreferences is Android's, so this stays in the app; which keys travel and which of the two
 * backup fields they belong in is [BackupPreferencePolicy], shared with iOS.
 */
@OptIn(ExperimentalSerializationApi::class)
object PreferenceBackup {
    /** Returns the settings for backup field 104 and field 900, in that order. */
    fun dump(context: Context): Pair<List<BackupPreference>, List<BackupPreference>> =
        prefs(context).all
            .filterKeys { BackupPreferencePolicy.isBackedUp(it) }
            .mapNotNull { (key, value) -> value.toPreferenceValue()?.let { BackupPreference(key, it) } }
            .sortedBy { it.key }
            .partition { BackupPreferencePolicy.isShared(it.key) }

    /**
     * Writes back what this app recognises.
     *
     * A backup written by another fork carries its own keys in field 104, and a key we do not read
     * the same way would land as the wrong type or the wrong meaning, so only the shared set is
     * taken from there. Field 900 is ours, so anything in it is accepted as long as it is a key
     * this app would have written itself.
     */
    fun restore(
        context: Context,
        shared: List<BackupPreference>,
        own: List<BackupPreference>
    ) {
        val accepted =
            shared.filter { BackupPreferencePolicy.isShared(it.key) } +
                own.filter { BackupPreferencePolicy.isBackedUp(it.key) }
        if (accepted.isEmpty()) return

        val editor = prefs(context).edit()
        accepted.forEach { editor.put(it.key, it.value) }
        editor.apply()
    }

    private fun prefs(context: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    private fun Any?.toPreferenceValue(): PreferenceValue? =
        when (this) {
            is Int -> IntPreferenceValue(this)
            is Long -> LongPreferenceValue(this)
            is Float -> FloatPreferenceValue(this)
            is String -> StringPreferenceValue(this)
            is Boolean -> BooleanPreferenceValue(this)
            is Set<*> -> StringSetPreferenceValue(filterIsInstance<String>().toSet())
            else -> null
        }

    private fun SharedPreferences.Editor.put(
        key: String,
        value: PreferenceValue
    ) {
        // remove() first: SharedPreferences keeps a key's old type across a put of a different
        // one, and reading an Int back out of a key still holding a Boolean throws.
        remove(key)
        when (value) {
            is IntPreferenceValue -> putInt(key, value.value)
            is LongPreferenceValue -> putLong(key, value.value)
            is FloatPreferenceValue -> putFloat(key, value.value)
            is StringPreferenceValue -> putString(key, value.value)
            is BooleanPreferenceValue -> putBoolean(key, value.value)
            is StringSetPreferenceValue -> putStringSet(key, value.value)
        }
    }
}
