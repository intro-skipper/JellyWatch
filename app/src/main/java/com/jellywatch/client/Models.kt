package com.jellywatch.client

import org.json.JSONObject

data class Session(
    val server: String,
    val token: String,
    val userId: String,
    val userName: String,
    val deviceId: String
)

data class DiscoveredServer(
    val name: String,
    val url: String,
    val version: String,
    val id: String
)

data class JellyItem(
    val id: String,
    val name: String,
    val type: String,
    val overview: String,
    val seriesName: String?,
    val seriesId: String?,
    val seasonId: String?,
    val parentId: String?,
    val productionYear: Int?,
    val indexNumber: Int?,
    val parentIndexNumber: Int?,
    val runtimeTicks: Long,
    val playbackTicks: Long,
    val playedPercentage: Double,
    val primaryImageTag: String?,
    val backdropImageTag: String?,
    val mediaType: String?,
    val mediaSourceId: String?
) {
    val isPlayable: Boolean
        get() = type in setOf("Movie", "Episode", "Audio", "MusicVideo", "Video", "Trailer")

    val subtitle: String
        get() = when {
            type == "Episode" && seriesName != null -> {
                val episode = listOfNotNull(
                    parentIndexNumber?.let { "S${it.toString().padStart(2, '0')}" },
                    indexNumber?.let { "E${it.toString().padStart(2, '0')}" }
                ).joinToString("")
                listOf(seriesName, episode).filter { it.isNotBlank() }.joinToString(" • ")
            }
            productionYear != null -> "$type • $productionYear"
            else -> type
        }

    companion object {
        fun fromJson(json: JSONObject): JellyItem {
            val userData = json.optJSONObject("UserData") ?: JSONObject()
            val imageTags = json.optJSONObject("ImageTags")
            val backdropTags = json.optJSONArray("BackdropImageTags")
            val mediaSources = json.optJSONArray("MediaSources")
            return JellyItem(
                id = json.optString("Id"),
                name = json.optString("Name", "Untitled"),
                type = json.optString("Type", "Media"),
                overview = json.optString("Overview"),
                seriesName = json.optString("SeriesName").takeIf { it.isNotBlank() },
                seriesId = json.optString("SeriesId").takeIf { it.isNotBlank() },
                seasonId = json.optString("SeasonId").takeIf { it.isNotBlank() },
                parentId = json.optString("ParentId").takeIf { it.isNotBlank() },
                productionYear = json.optInt("ProductionYear").takeIf { it > 0 },
                indexNumber = json.optInt("IndexNumber").takeIf { it > 0 },
                parentIndexNumber = json.optInt("ParentIndexNumber").takeIf { it > 0 },
                runtimeTicks = json.optLong("RunTimeTicks"),
                playbackTicks = userData.optLong("PlaybackPositionTicks"),
                playedPercentage = userData.optDouble("PlayedPercentage", 0.0),
                primaryImageTag = json.optString("PrimaryImageTag").takeIf { it.isNotBlank() }
                    ?: imageTags?.optString("Primary")?.takeIf { it.isNotBlank() },
                backdropImageTag = backdropTags?.optString(0)?.takeIf { it.isNotBlank() }
                    ?: imageTags?.optString("Backdrop")?.takeIf { it.isNotBlank() },
                mediaType = json.optString("MediaType").takeIf { it.isNotBlank() },
                mediaSourceId = mediaSources?.optJSONObject(0)?.optString("Id")?.takeIf { it.isNotBlank() }
            )
        }
    }
}

data class MediaSegment(
    val type: String,
    val startTicks: Long,
    val endTicks: Long
) {
    val label: String
        get() = when (type.lowercase()) {
            "intro" -> "Skip intro"
            "outro" -> "Skip outro"
            "recap" -> "Skip recap"
            "preview" -> "Skip preview"
            "commercial" -> "Skip ad"
            else -> "Skip segment"
        }
}
