package eu.kanade.tachiyomi.util.system

import android.util.Log
import eu.kanade.tachiyomi.source.Source
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

private const val GUARD_TAG = "MangaUpdateGuard"

/**
 * A [ConcurrentHashMap] that stores entries normally but whose [putIfAbsent] always reports
 * the key as newly added (returns null). some extensions guard getMangaUpdate with
 *
 *     check(updatesInFlight.putIfAbsent(manga.url, true) == null)
 *
 * to throw when the same manga is updated concurrently. This fork opens the manga info and
 * chapter list in separate tabs, each calling getMangaUpdate for the same manga on its own
 * IO thread, so that check fails. The real put still happens (so remove() in the finally block
 * and any other reads stay consistent); only the return value is spoofed so the check passes.
 */
private class AlwaysAbsentMap : ConcurrentHashMap<Any?, Any?>() {
    override fun putIfAbsent(key: Any?, value: Any?): Any? {
        super.putIfAbsent(key, value)
        return null
    }
}

/**
 * Neutralises the private `updatesInFlight` guard map an extension base uses to throw on
 * concurrent getMangaUpdate calls for the same manga.
 *
 * The field is private, so a minified extension APK renames it (e.g. to `a`) and matching by
 * name fails. Instead we anchor on the class that declares `getMangaUpdate` — that name is an
 * override of the app's [Source] interface and cannot be renamed — and replace the
 * [ConcurrentHashMap] field(s) on it. The write is read back to confirm it took effect, since
 * reflecting into a final field can silently fail on some runtimes. Every outcome is logged so
 * failures are diagnosable from logcat. No-op for sources without such a field.
 */
fun Source.bypassMangaUpdateGuard() {
    val hierarchy = generateSequence(javaClass as Class<*>) { it.superclass }.toList()

    // Classes that declare getMangaUpdate (the guard lives on one of them). Fall back to the
    // whole hierarchy if the method can't be located, so a name match can still fire.
    val guardClasses = hierarchy
        .filter { cls -> cls.declaredMethods.any { it.name == "getMangaUpdate" } }
        .ifEmpty { hierarchy }

    val candidates = guardClasses
        .flatMap { it.declaredFields.asList() }
        .filter { it.name == "updatesInFlight" || it.type == ConcurrentHashMap::class.java }
        .distinct()

    if (candidates.isEmpty()) {
        Log.w(GUARD_TAG, "no guard field on ${javaClass.name}; skipping")
        return
    }

    for (field in candidates) {
        replaceWithPermissiveMap(field)
    }
}

private fun Source.replaceWithPermissiveMap(field: Field) {
    val name = "${field.declaringClass.simpleName}.${field.name}"
    try {
        field.isAccessible = true
        field.set(this, AlwaysAbsentMap())
        if (field.get(this) is AlwaysAbsentMap) {
            Log.i(GUARD_TAG, "neutralised $name on ${javaClass.name}")
        } else {
            Log.e(GUARD_TAG, "write to $name did not stick on ${javaClass.name}")
        }
    } catch (e: Throwable) {
        Log.e(GUARD_TAG, "could not replace $name on ${javaClass.name}", e)
    }
}
