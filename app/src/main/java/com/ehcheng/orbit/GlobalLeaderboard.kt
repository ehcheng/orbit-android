package com.ehcheng.orbit

import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class GlobalEntry(
    val name: String,
    val score: Int
)

data class BracketResult(
    val yourRank: Int,
    val totalScores: Int,
    val above: List<GlobalEntry>,
    val below: List<GlobalEntry>
)

/**
 * Client for the Orbit global leaderboard API.
 * All calls are async and fail silently — global is optional, never blocks gameplay.
 */
class GlobalLeaderboard {

    companion object {
        private const val BASE_URL = "https://orbit-leaderboard.public-a29.workers.dev"
        private const val TIMEOUT_MS = 5000
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    // Cached data
    var globalTop: List<GlobalEntry> = emptyList()
        private set
    var lastBracket: BracketResult? = null
        private set
    var lastGlobalRank: Int = 0
        private set
    var totalGlobalScores: Int = 0
        private set

    /** Submit a score and get global rank back */
    fun submitScore(name: String, score: Int, onResult: ((Int) -> Unit)? = null) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("name", name)
                    put("score", score)
                }
                val response = post("/score", json.toString())
                if (response != null) {
                    val obj = JSONObject(response)
                    val rank = obj.optInt("global_rank", 0)
                    lastGlobalRank = rank
                    onResult?.invoke(rank)
                }
                // Refresh top list after submitting
                fetchTop()
            } catch (_: Exception) {}
        }
    }

    /** Fetch top N global scores */
    fun fetchTop(limit: Int = 10) {
        scope.launch {
            try {
                val response = get("/leaderboard?limit=$limit")
                if (response != null) {
                    val obj = JSONObject(response)
                    val arr = obj.getJSONArray("leaderboard")
                    globalTop = parseEntries(arr)
                }
            } catch (_: Exception) {}
        }
    }

    /** Fetch bracket around a score */
    fun fetchBracket(score: Int, context: Int = 5) {
        scope.launch {
            try {
                val response = get("/bracket?score=$score&context=$context")
                if (response != null) {
                    val obj = JSONObject(response)
                    lastBracket = BracketResult(
                        yourRank = obj.optInt("your_rank", 0),
                        totalScores = obj.optInt("total_scores", 0),
                        above = parseEntries(obj.getJSONArray("above")),
                        below = parseEntries(obj.getJSONArray("below"))
                    )
                    lastGlobalRank = lastBracket?.yourRank ?: 0
                    totalGlobalScores = lastBracket?.totalScores ?: 0
                }
            } catch (_: Exception) {}
        }
    }

    /** Get rank for a score (lightweight, no bracket) */
    fun fetchRank(score: Int, onResult: ((Int, Int) -> Unit)? = null) {
        scope.launch {
            try {
                val response = get("/rank?score=$score")
                if (response != null) {
                    val obj = JSONObject(response)
                    val rank = obj.optInt("global_rank", 0)
                    val total = obj.optInt("total_scores", 0)
                    lastGlobalRank = rank
                    totalGlobalScores = total
                    onResult?.invoke(rank, total)
                }
            } catch (_: Exception) {}
        }
    }

    private fun parseEntries(arr: JSONArray): List<GlobalEntry> {
        return (0 until arr.length()).map { i ->
            val entry = arr.getJSONObject(i)
            GlobalEntry(
                name = entry.optString("name", "???"),
                score = entry.optInt("score", 0)
            )
        }
    }

    private fun get(path: String): String? {
        val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.requestMethod = "GET"
        return try {
            if (conn.responseCode == 200) {
                BufferedReader(InputStreamReader(conn.inputStream)).readText()
            } else null
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, body: String): String? {
        val conn = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        return try {
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            if (conn.responseCode == 200) {
                BufferedReader(InputStreamReader(conn.inputStream)).readText()
            } else null
        } finally {
            conn.disconnect()
        }
    }

    fun release() {
        scope.cancel()
    }
}
