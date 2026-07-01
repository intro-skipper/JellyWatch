package com.jellywatch.client

import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class JellyfinApi(private val session: Session) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun authenticate(userName: String, password: String): Session {
        val body = JSONObject()
            .put("Username", userName)
            .put("Pw", password)
            .toString()
        val response = request("Users/AuthenticateByName", "POST", body, includeToken = false)
        val user = response.getJSONObject("User")
        return session.copy(
            token = response.getString("AccessToken"),
            userId = user.getString("Id"),
            userName = user.optString("Name", userName)
        )
    }

    data class QuickConnect(val code: String, val secret: String)

    fun initiateQuickConnect(): QuickConnect {
        val response = request("QuickConnect/Initiate", "POST", "{}", includeToken = false)
        return QuickConnect(
            code = response.getString("Code"),
            secret = response.getString("Secret")
        )
    }

    fun isQuickConnectApproved(secret: String): Boolean {
        val encoded = Uri.encode(secret)
        return request("QuickConnect/Connect?Secret=$encoded", includeToken = false)
            .optBoolean("Authenticated", false)
    }

    fun authenticateQuickConnect(secret: String): Session {
        val body = JSONObject().put("Secret", secret).toString()
        val response = request("Users/AuthenticateWithQuickConnect", "POST", body, includeToken = false)
        val user = response.getJSONObject("User")
        return session.copy(
            token = response.getString("AccessToken"),
            userId = user.getString("Id"),
            userName = user.optString("Name", "Jellyfin")
        )
    }

    fun resumeItems(limit: Int = 12): List<JellyItem> = itemsFrom(
        request("Users/${session.userId}/Items/Resume?Limit=$limit&MediaTypes=Video&Fields=${fields()}")
    )

    fun nextUp(limit: Int = 12): List<JellyItem> = itemsFrom(
        request("Shows/NextUp?UserId=${session.userId}&Limit=$limit&Fields=${fields()}")
    )

    fun libraries(): List<JellyItem> = itemsFrom(
        request("Users/${session.userId}/Views?Fields=PrimaryImageAspectRatio")
    )

    fun latest(limit: Int = 12): List<JellyItem> = itemsFrom(
        request("Users/${session.userId}/Items/Latest?Limit=$limit&GroupItems=false&Fields=${fields()}"),
        rootArray = true
    )

    fun children(parentId: String, limit: Int = 60): List<JellyItem> {
        val query = Uri.Builder()
            .appendQueryParameter("ParentId", parentId)
            // Preserve Jellyfin's hierarchy: library → series → seasons → episodes.
            .appendQueryParameter("Recursive", "false")
            .appendQueryParameter("Limit", limit.toString())
            .appendQueryParameter("SortBy", "SortName")
            .appendQueryParameter("SortOrder", "Ascending")
            .appendQueryParameter(
                "IncludeItemTypes",
                "Movie,Series,Season,Episode,Audio,MusicAlbum,MusicArtist,Video,Folder,BoxSet,Playlist"
            )
            .appendQueryParameter("Fields", fields())
            .build().encodedQuery
        return itemsFrom(request("Users/${session.userId}/Items?$query"))
    }

    fun search(term: String, limit: Int = 40): List<JellyItem> {
        val query = Uri.Builder()
            .appendQueryParameter("SearchTerm", term)
            .appendQueryParameter("Recursive", "true")
            .appendQueryParameter("Limit", limit.toString())
            .appendQueryParameter("IncludeItemTypes", "Movie,Series,Episode,Audio,MusicAlbum,MusicArtist,Video")
            .appendQueryParameter("Fields", fields())
            .build().encodedQuery
        return itemsFrom(request("Users/${session.userId}/Items?$query"))
    }

    fun item(id: String): JellyItem = JellyItem.fromJson(
        request("Users/${session.userId}/Items/$id")
    )

    fun mediaSegments(itemId: String): List<MediaSegment> {
        val response = request("MediaSegments/$itemId")
        val items = response.optJSONArray("Items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val segment = items.optJSONObject(index) ?: return@mapNotNull null
            val startTicks = segment.optLong("StartTicks", -1L)
            val endTicks = segment.optLong("EndTicks", -1L)
            if (startTicks < 0 || endTicks <= startTicks) return@mapNotNull null
            MediaSegment(
                type = segment.optString("Type", "Segment"),
                startTicks = startTicks,
                endTicks = endTicks
            )
        }.sortedBy { it.startTicks }
    }

    fun imageUrl(item: JellyItem, width: Int = 260, backdrop: Boolean = false): String? {
        val imageType = if (backdrop && item.backdropImageTag != null) "Backdrop" else "Primary"
        val tag = if (imageType == "Backdrop") item.backdropImageTag else item.primaryImageTag
        if (tag == null) return null
        return Uri.parse("${session.server}/Items/${item.id}/Images/$imageType").buildUpon()
            .appendQueryParameter("tag", tag)
            .appendQueryParameter("fillWidth", width.toString())
            .appendQueryParameter("quality", "80")
            .appendApiKey()
            .build().toString()
    }

    fun playbackUrl(item: JellyItem, playSessionId: String): String {
        val sourceId = item.mediaSourceId ?: item.id
        val base = if (item.type == "Audio" || item.mediaType == "Audio") {
            "${session.server}/Audio/${item.id}/universal"
        } else {
            "${session.server}/Videos/${item.id}/master.m3u8"
        }
        return Uri.parse(base).buildUpon()
            .appendQueryParameter("UserId", session.userId)
            .appendQueryParameter("DeviceId", session.deviceId)
            .appendQueryParameter("PlaySessionId", playSessionId)
            .appendQueryParameter("MediaSourceId", sourceId)
            .appendQueryParameter("VideoCodec", "h264")
            .appendQueryParameter("AudioCodec", "aac")
            .appendQueryParameter("VideoBitRate", "2200000")
            .appendQueryParameter("AudioBitRate", "128000")
            .appendQueryParameter("MaxStreamingBitrate", "2328000")
            .appendQueryParameter("MaxWidth", "480")
            .appendQueryParameter("MaxHeight", "480")
            .appendQueryParameter("TranscodingMaxAudioChannels", "2")
            .appendQueryParameter("SegmentContainer", "ts")
            .appendQueryParameter("MinSegments", "1")
            .appendQueryParameter("BreakOnNonKeyFrames", "true")
            .appendApiKey()
            .build().toString()
    }

    fun directPlaybackUrl(item: JellyItem): String {
        val sourceId = item.mediaSourceId ?: item.id
        val base = if (item.type == "Audio" || item.mediaType == "Audio") {
            "${session.server}/Audio/${item.id}/stream"
        } else {
            "${session.server}/Videos/${item.id}/stream"
        }
        return Uri.parse(base).buildUpon()
            .appendQueryParameter("Static", "true")
            .appendQueryParameter("MediaSourceId", sourceId)
            .appendApiKey()
            .build().toString()
    }

    fun reportPlayback(
        event: String,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
        mediaSourceId: String
    ) {
        val body = JSONObject()
            .put("ItemId", itemId)
            .put("PlaySessionId", playSessionId)
            .put("PositionTicks", positionTicks)
            .put("CanSeek", true)
            .put("IsPaused", isPaused)
            .put("IsMuted", false)
            .put("PlayMethod", playMethod)
            .put("MediaSourceId", mediaSourceId)
            .toString()
        val path = if (event.isBlank()) "Sessions/Playing" else "Sessions/Playing/$event"
        runCatching { request(path, "POST", body) }
    }

    private fun request(
        path: String,
        method: String = "GET",
        jsonBody: String? = null,
        includeToken: Boolean = true
    ): JSONObject {
        val url = "${session.server}/${path.trimStart('/')}"
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", authorization(includeToken))
            .header("X-Emby-Authorization", authorization(includeToken))
        if (method == "POST") {
            builder.post((jsonBody ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(text).optString("Message") }.getOrNull()
                throw IOException(message?.takeIf { it.isNotBlank() } ?: "Server returned ${response.code}")
            }
            return if (text.isBlank()) JSONObject() else if (text.trimStart().startsWith("[")) {
                JSONObject().put("Items", JSONArray(text))
            } else JSONObject(text)
        }
    }

    private fun authorization(includeToken: Boolean): String {
        val token = if (includeToken && session.token.isNotBlank()) ", Token=\"${session.token}\"" else ""
        return "MediaBrowser Client=\"JellyWatch\", Device=\"Galaxy Watch\", DeviceId=\"${session.deviceId}\", Version=\"1.0.0\"$token"
    }

    private fun Uri.Builder.appendApiKey(): Uri.Builder {
        appendQueryParameter("ApiKey", session.token)
        return appendQueryParameter("api_key", session.token)
    }

    private fun itemsFrom(json: JSONObject, rootArray: Boolean = false): List<JellyItem> {
        val items = if (rootArray) json.optJSONArray("Items") else json.optJSONArray("Items")
        return items?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(JellyItem::fromJson)
            }
        } ?: emptyList()
    }

    private fun fields() = "Overview,PrimaryImageAspectRatio,MediaSources,MediaStreams,Path,ParentId,SeriesId,SeasonId"

    companion object {
        private val discoveryClient = OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .build()

        fun discoverServer(raw: String): DiscoveredServer {
            val server = normalizeServer(raw)
            val request = Request.Builder()
                .url("${server.trimEnd('/')}/System/Info/Public")
                .header("Accept", "application/json")
                .build()
            discoveryClient.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
                val json = JSONObject(text)
                val name = json.optString("ServerName").takeIf { it.isNotBlank() } ?: "Jellyfin"
                return DiscoveredServer(
                    name = name,
                    url = server,
                    version = json.optString("Version").takeIf { it.isNotBlank() } ?: "Unknown version",
                    id = json.optString("Id")
                )
            }
        }

        fun normalizeServer(raw: String): String {
            var server = raw.trim().trimEnd('/')
            if (!server.startsWith("http://") && !server.startsWith("https://")) {
                server = "http://$server"
            }
            val uri = Uri.parse(server)
            require(!uri.host.isNullOrBlank()) { "Enter a valid Jellyfin server address" }
            return server
        }
    }
}
