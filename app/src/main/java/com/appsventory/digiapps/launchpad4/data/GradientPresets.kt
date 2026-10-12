package com.appsventory.digiapps.launchpad4.data

/**
 * Curated wallpaper gradient palette with light, colorful, and dark groups.
 * Stored gradient indices are coerced into [ALL] range on load so legacy prefs
 * cannot crash after the palette is replaced.
 */
object GradientPresets {

    data class Group(val label: String, val colors: List<IntArray>)

    val GROUPS: List<Group> = listOf(
        Group(
            "Light & Neutral",
            listOf(
                intArrayOf(0xFFF7F9FC.toInt(), 0xFFE4E9F0.toInt()), // Arctic White
                intArrayOf(0xFFF2F0F5.toInt(), 0xFFDCD8E4.toInt()), // Pearl Mist
                intArrayOf(0xFFF5EFE6.toInt(), 0xFFE0D4C4.toInt()), // Warm Sand
                intArrayOf(0xFFFFF8EE.toInt(), 0xFFF0E6D4.toInt()), // Soft Ivory
                intArrayOf(0xFFEEF1F5.toInt(), 0xFFD0D6E0.toInt()), // Silver Cloud
                intArrayOf(0xFFEDE9E3.toInt(), 0xFFD4CFC6.toInt())  // Pale Stone
            )
        ),
        Group(
            "Ocean & Sky",
            listOf(
                intArrayOf(0xFFD6EAF8.toInt(), 0xFFA8C8E8.toInt()), // Sky Blue
                intArrayOf(0xFFD0E4F0.toInt(), 0xFF9BB8D0.toInt()), // Arctic Blue
                intArrayOf(0xFFD4F0F2.toInt(), 0xFF9ED4D8.toInt()), // Soft Cyan
                intArrayOf(0xFF1A6B6E.toInt(), 0xFF0C3A3C.toInt()), // Ocean Teal
                intArrayOf(0xFF2A5A8A.toInt(), 0xFF153050.toInt()), // Coastal Blue
                intArrayOf(0xFFB8DCE0.toInt(), 0xFF7AB0B8.toInt())  // Misty Aqua
            )
        ),
        Group(
            "Nature",
            listOf(
                intArrayOf(0xFFD4E4D6.toInt(), 0xFFA8C4AC.toInt()), // Sage Green
                intArrayOf(0xFFD0EDE4.toInt(), 0xFF9AD4C4.toInt()), // Mint Leaf
                intArrayOf(0xFFD8E0C8.toInt(), 0xFFB0BC98.toInt()), // Soft Olive
                intArrayOf(0xFF1C4A32.toInt(), 0xFF0C2418.toInt()), // Forest Green
                intArrayOf(0xFF2E8A6A.toInt(), 0xFF184A38.toInt()), // Fresh Jade
                intArrayOf(0xFFC8D8CC.toInt(), 0xFF98B4A4.toInt())  // Eucalyptus
            )
        ),
        Group(
            "Warm & Sunset",
            listOf(
                intArrayOf(0xFFF8E0D0.toInt(), 0xFFE8B898.toInt()), // Peach Glow
                intArrayOf(0xFFF0C8C0.toInt(), 0xFFD89890.toInt()), // Soft Coral
                intArrayOf(0xFFE8C080.toInt(), 0xFFC08840.toInt()), // Amber
                intArrayOf(0xFFC07050.toInt(), 0xFF703828.toInt()), // Terracotta
                intArrayOf(0xFFE89050.toInt(), 0xFFB05028.toInt()), // Sunset Orange
                intArrayOf(0xFFE8B0B8.toInt(), 0xFFC07888.toInt())  // Warm Rose
            )
        ),
        Group(
            "Pastel & Creative",
            listOf(
                intArrayOf(0xFFE8DCF0.toInt(), 0xFFC8B0D8.toInt()), // Lavender Mist
                intArrayOf(0xFFD8C8E8.toInt(), 0xFFB098D0.toInt()), // Lilac
                intArrayOf(0xFFF0D0D8.toInt(), 0xFFD8A0B0.toInt()), // Blush Pink
                intArrayOf(0xFFD0E0F0.toInt(), 0xFFA0B8D8.toInt()), // Powder Blue
                intArrayOf(0xFFF8F0C8.toInt(), 0xFFE0D090.toInt()), // Soft Butter Yellow
                intArrayOf(0xFFD0EDE0.toInt(), 0xFFA0D0B8.toInt())  // Pastel Mint
            )
        ),
        Group(
            "Dark & Premium",
            listOf(
                intArrayOf(0xFF2A2D34.toInt(), 0xFF12141A.toInt()), // Midnight Graphite
                intArrayOf(0xFF1A2F45.toInt(), 0xFF0A1520.toInt()), // Deep Ocean
                intArrayOf(0xFF2A2F48.toInt(), 0xFF12162A.toInt()), // Slate Indigo
                intArrayOf(0xFF342438.toInt(), 0xFF180F1C.toInt()), // Plum Dusk
                intArrayOf(0xFF1C3328.toInt(), 0xFF0C1812.toInt()), // Forest Night
                intArrayOf(0xFF2E2E2E.toInt(), 0xFF121212.toInt())  // Charcoal
            )
        )
    )

    /** Flatten all presets (stable order) for index prefs. */
    val ALL: List<IntArray> = GROUPS.flatMap { it.colors }

    val NAMES: List<String> = listOf(
        "Arctic White", "Pearl Mist", "Warm Sand", "Soft Ivory", "Silver Cloud", "Pale Stone",
        "Sky Blue", "Arctic Blue", "Soft Cyan", "Ocean Teal", "Coastal Blue", "Misty Aqua",
        "Sage Green", "Mint Leaf", "Soft Olive", "Forest Green", "Fresh Jade", "Eucalyptus",
        "Peach Glow", "Soft Coral", "Amber", "Terracotta", "Sunset Orange", "Warm Rose",
        "Lavender Mist", "Lilac", "Blush Pink", "Powder Blue", "Soft Butter Yellow", "Pastel Mint",
        "Midnight Graphite", "Deep Ocean", "Slate Indigo", "Plum Dusk", "Forest Night", "Charcoal"
    )
}
