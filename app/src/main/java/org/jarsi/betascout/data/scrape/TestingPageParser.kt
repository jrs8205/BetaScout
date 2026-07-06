package org.jarsi.betascout.data.scrape

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jarsi.betascout.domain.LiveBetaStatus
import org.jarsi.betascout.domain.ObservedMembership

/** Outcome of parsing one authenticated Play testing page. */
data class TestingPageResult(
    val liveStatus: LiveBetaStatus,
    val membership: ObservedMembership,
    val needsLogin: Boolean,
)

/**
 * Parses the HTML of `play.google.com/apps/testing/<pkg>` (loaded with the user's
 * Google session) into the testing program's live status and the user's membership.
 * Pure and Android-free so it can be unit-tested against saved fixtures.
 *
 * Detection keys on the same markers the reference app relies on: a sign-in form
 * (`gaia_loginform`), a leave form (already a tester), a join form (open, not joined),
 * and otherwise the status text on the page.
 */
object TestingPageParser {

    private val FULL_PHRASES = listOf("program is full", "maximum number of testers")
    private val CLOSED_PHRASES = listOf(
        "no longer accepting", "not accepting new testers", "testing program is closed",
    )
    private val MISSING_PHRASES = listOf(
        "not found", "isn't available", "is not available", "no longer available",
    )

    fun parse(html: String): TestingPageResult {
        if (html.isBlank()) return inconclusive()

        val doc = Jsoup.parse(html)
        val text = doc.text().lowercase()

        return when {
            hasMarker(doc, "gaia_loginform") ->
                TestingPageResult(LiveBetaStatus.UNKNOWN, ObservedMembership.UNKNOWN, needsLogin = true)

            hasMarker(doc, "leaveForm") ->
                TestingPageResult(LiveBetaStatus.OPEN, ObservedMembership.JOINED, needsLogin = false)

            hasMarker(doc, "joinForm") ->
                TestingPageResult(LiveBetaStatus.OPEN, ObservedMembership.NOT_JOINED, needsLogin = false)

            text.containsAny(FULL_PHRASES) ->
                TestingPageResult(LiveBetaStatus.FULL, ObservedMembership.NOT_JOINED, needsLogin = false)

            text.containsAny(CLOSED_PHRASES) ->
                TestingPageResult(LiveBetaStatus.CLOSED, ObservedMembership.NOT_JOINED, needsLogin = false)

            text.containsAny(MISSING_PHRASES) ->
                TestingPageResult(LiveBetaStatus.NO_PROGRAM, ObservedMembership.UNKNOWN, needsLogin = false)

            else -> inconclusive()
        }
    }

    private fun inconclusive() =
        TestingPageResult(LiveBetaStatus.UNKNOWN, ObservedMembership.UNKNOWN, needsLogin = false)

    /** True if any element carries [token] in its id, class, or name (case-insensitive). */
    private fun hasMarker(doc: Document, token: String): Boolean {
        val needle = token.lowercase()
        return doc.allElements.any { el ->
            sequenceOf(el.id(), el.className(), el.attr("name"))
                .any { it.lowercase().contains(needle) }
        }
    }

    private fun String.containsAny(phrases: List<String>): Boolean =
        phrases.any { this.contains(it) }
}
