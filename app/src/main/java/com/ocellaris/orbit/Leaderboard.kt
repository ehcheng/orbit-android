package com.ocellaris.orbit

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class LeaderboardEntry(
    val name: String,
    val score: Int,
    val timestamp: Long = System.currentTimeMillis()
)

class Leaderboard(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("orbit_leaderboard", Context.MODE_PRIVATE)

    private val maxEntries = 10

    fun getEntries(): List<LeaderboardEntry> {
        val json = prefs.getString("entries", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                LeaderboardEntry(
                    name = obj.getString("name"),
                    score = obj.getInt("score"),
                    timestamp = obj.optLong("timestamp", 0L)
                )
            }.sortedByDescending { it.score }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addEntry(name: String, score: Int) {
        val entries = getEntries().toMutableList()
        entries.add(LeaderboardEntry(name, score))
        entries.sortByDescending { it.score }
        val trimmed = entries.take(maxEntries)
        save(trimmed)
    }

    fun isHighScore(score: Int): Boolean {
        val entries = getEntries()
        return entries.size < maxEntries || (entries.isNotEmpty() && score > entries.last().score) || entries.isEmpty()
    }

    fun getLastName(): String {
        return prefs.getString("last_name", "") ?: ""
    }

    fun setLastName(name: String) {
        prefs.edit().putString("last_name", name).apply()
    }

    private fun save(entries: List<LeaderboardEntry>) {
        val arr = JSONArray()
        for (entry in entries) {
            val obj = JSONObject()
            obj.put("name", entry.name)
            obj.put("score", entry.score)
            obj.put("timestamp", entry.timestamp)
            arr.put(obj)
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }
}
