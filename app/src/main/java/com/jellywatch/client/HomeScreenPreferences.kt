package com.jellywatch.client

import android.content.Context

enum class HomeSection(val id: String, val title: String) {
    ContinueWatching("continue_watching", "Continue watching"),
    Libraries("libraries", "Libraries"),
    NextUp("next_up", "Next up"),
    RecentlyAdded("recently_added", "Recently added")
}

data class HomeSectionSetting(
    val section: HomeSection,
    val enabled: Boolean
)

class HomeScreenPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("jellywatch_home", Context.MODE_PRIVATE)

    fun load(): List<HomeSectionSetting> {
        val saved = prefs.getString(KEY_SECTIONS, null) ?: return defaults()
        val parsed = saved.split(',').mapNotNull { entry ->
            val parts = entry.split(':', limit = 2)
            val section = HomeSection.entries.firstOrNull { it.id == parts.firstOrNull() }
                ?: return@mapNotNull null
            HomeSectionSetting(section, parts.getOrNull(1)?.toBooleanStrictOrNull() ?: false)
        }.distinctBy { it.section }

        return parsed + defaults().filter { default -> parsed.none { it.section == default.section } }
    }

    fun save(settings: List<HomeSectionSetting>) {
        val value = settings.joinToString(",") { "${it.section.id}:${it.enabled}" }
        prefs.edit().putString(KEY_SECTIONS, value).apply()
    }

    private fun defaults() = listOf(
        HomeSectionSetting(HomeSection.ContinueWatching, true),
        HomeSectionSetting(HomeSection.Libraries, true),
        HomeSectionSetting(HomeSection.NextUp, false),
        HomeSectionSetting(HomeSection.RecentlyAdded, false)
    )

    companion object {
        private const val KEY_SECTIONS = "sections"
    }
}
