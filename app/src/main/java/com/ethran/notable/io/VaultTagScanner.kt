package com.ethran.notable.io

import android.os.Environment
import io.shipbook.shipbooksdk.ShipBook
import java.io.File

private val log = ShipBook.getLogger("VaultTagScanner")

data class TagScore(
    val tag: String,
    val frequency: Int,
    val lastSeenMs: Long
)

object VaultTagScanner {

    // Cached tags — pre-populated on app start
    @Volatile
    var cachedTags: List<String> = emptyList()
        private set

    /**
     * Refresh the tag cache in the background. Call from app initialization.
     */
    fun refreshCache(inboxPath: String) {
        cachedTags = scanTags(inboxPath)
        log.i("Tag cache refreshed: ${cachedTags.size} tags")
    }

    /**
     * Scans markdown files in the inbox folder, parses YAML frontmatter tags,
     * and returns tags ranked by frequency × recency.
     */
    fun scanTags(inboxPath: String, limit: Int = 30): List<String> {
        val dir = if (inboxPath.startsWith("/")) {
            File(inboxPath)
        } else {
            File(Environment.getExternalStorageDirectory(), inboxPath)
        }

        if (!dir.exists() || !dir.isDirectory) {
            log.i("Inbox directory not found: ${dir.absolutePath}")
            return emptyList()
        }

        val tagScores = mutableMapOf<String, TagScore>()

        val mdFiles = dir.listFiles { f -> f.extension == "md" } ?: emptyArray()
        log.i("Scanning ${mdFiles.size} markdown files for tags")

        for (file in mdFiles) {
            val tags = parseFrontmatterTags(file)
            val fileTime = file.lastModified()
            for (tag in tags) {
                val normalized = tag.lowercase().trim()
                if (normalized.isEmpty()) continue
                val existing = tagScores[normalized]
                tagScores[normalized] = TagScore(
                    tag = normalized,
                    frequency = (existing?.frequency ?: 0) + 1,
                    lastSeenMs = maxOf(existing?.lastSeenMs ?: 0L, fileTime)
                )
            }
        }

        if (tagScores.isEmpty()) return emptyList()

        // Score: frequency * recency weight (more recent = higher weight)
        val now = System.currentTimeMillis()
        val maxAge = 90.0 * 24 * 60 * 60 * 1000 // 90 days in ms

        return tagScores.values
            .sortedByDescending { score ->
                val ageMs = (now - score.lastSeenMs).coerceAtLeast(0)
                val recencyWeight = 1.0 - (ageMs / maxAge).coerceAtMost(1.0)
                score.frequency * (0.3 + 0.7 * recencyWeight)
            }
            .take(limit)
            .map { it.tag }
    }

    private fun parseFrontmatterTags(file: File): List<String> {
        try {
            val lines = file.readLines()
            if (lines.isEmpty() || lines[0].trim() != "---") return emptyList()

            var inFrontmatter = true
            var inTagsBlock = false
            val tags = mutableListOf<String>()

            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.trim() == "---") break // end of frontmatter

                if (line.startsWith("tags:")) {
                    // Inline tags: tags: [a, b, c] or tags: a, b
                    val inline = line.substringAfter("tags:").trim()
                    if (inline.isNotEmpty()) {
                        // Handle [a, b, c] or a, b formats
                        val cleaned = inline.trimStart('[').trimEnd(']')
                        tags.addAll(
                            cleaned.split(",")
                                .map { it.trim().trimStart('#') }
                                .filter { it.isNotEmpty() }
                        )
                    }
                    inTagsBlock = true
                    continue
                }

                if (inTagsBlock) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("- ")) {
                        tags.add(trimmed.removePrefix("- ").trim().trimStart('#'))
                    } else {
                        inTagsBlock = false
                    }
                }
            }

            return tags
        } catch (e: Exception) {
            log.e("Failed to parse tags from ${file.name}: ${e.message}")
            return emptyList()
        }
    }
}
