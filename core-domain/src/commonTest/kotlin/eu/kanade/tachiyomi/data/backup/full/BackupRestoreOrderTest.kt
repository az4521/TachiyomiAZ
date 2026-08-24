package eu.kanade.tachiyomi.data.backup.full

import eu.kanade.tachiyomi.data.backup.full.models.BackupManga
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
class BackupRestoreOrderTest {
    @Test
    fun `merged source entries are restored after their children`() {
        val merged = BackupManga(source = 999L, url = "{\"c\":[{\"s\":1,\"u\":\"/child\"}]}")
        val ordinary = BackupManga(source = 1L, url = "/ordinary")

        assertEquals(
            listOf(ordinary, merged),
            sortBackupMangaForRestore(listOf(merged, ordinary))
        )
    }

    @Test
    fun `malformed JSON manga URLs remain ordinary entries`() {
        val malformed = BackupManga(source = 1L, url = "{not-a-merged-entry")

        assertEquals(listOf(malformed), sortBackupMangaForRestore(listOf(malformed)))
    }
}
