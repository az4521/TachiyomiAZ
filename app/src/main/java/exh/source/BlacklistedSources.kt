package exh.source

import exh.MERGED_SOURCE_ID

object BlacklistedSources {
    val NHENTAI_EXT_SOURCES =
        listOf(
            3122156392225024195,
            4726175775739752699,
            2203215402871965477
        )
    val EHENTAI_EXT_SOURCES =
        listOf(
            8100626124886895451,
            57122881048805941,
            4678440076103929247,
            1876021963378735852,
            3955189842350477641,
            4348288691341764259,
            773611868725221145,
            5759417018342755550,
            825187715438990384,
            6116711405602166104,
            7151438547982231541,
            2171445159732592630,
            3032959619549451093,
            5980349886941016589,
            6073266008352078708,
            5499077866612745456,
            6140480779421365791
        )

    val BLACKLISTED_EXT_SOURCES =
        NHENTAI_EXT_SOURCES +
            EHENTAI_EXT_SOURCES

    val BLACKLISTED_EXTENSIONS =
        listOf(
            "eu.kanade.tachiyomi.extension.all.ehentai",
            "eu.kanade.tachiyomi.extension.all.nhentai"
        )

    var HIDDEN_SOURCES =
        listOf(
            MERGED_SOURCE_ID
        )
}
