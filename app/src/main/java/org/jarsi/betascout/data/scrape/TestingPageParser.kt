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

    /** The "App not available" testing page (signed-in): this account cannot access
     *  any testing program for the app — none is public, or it is invite-only. The
     *  phrase avoids the apostrophe in "isn't", which Google could typeset either
     *  straight or curly. */
    private val NOT_ELIGIBLE_PHRASES = listOf(
        "currently eligible for this app",
    )

    /** Sections that only appear on the public store-listing page, never on a testing page. */
    private val STORE_PAGE_PHRASES = listOf(
        "about this app", "data safety", "what's new", "ratings and reviews",
    )

    /** Any of these means the page still offers a testing affordance, so it is NOT a
     *  plain store page — used to keep the generic-page heuristic conservative. */
    private val TESTING_PHRASES = listOf(
        "testing program", "become a tester", "you're a tester", "you are a tester",
        "leave the test", "join the test",
    )

    fun parse(html: String): TestingPageResult {
        if (html.isBlank()) return inconclusive()

        val doc = Jsoup.parse(html)
        val text = doc.text().lowercase()

        return when {
            // Legacy sign-in page marker, plus the identifier input that Google's
            // current sign-in flow (accounts.google.com/v3/signin) renders instead.
            hasMarker(doc, "gaia_loginform") || hasMarker(doc, "identifierId") ->
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

            // "App not available … not currently eligible": no joinable program for
            // this account. Mentions testing vocabulary in its helper text, so it
            // must be recognized here — the store-page heuristic deliberately
            // backs off from anything that talks about testing.
            text.containsAny(NOT_ELIGIBLE_PHRASES) ->
                TestingPageResult(LiveBetaStatus.NO_PROGRAM, ObservedMembership.UNKNOWN, needsLogin = false)

            isGenericStoreDetailsPage(doc, text) ->
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

    /**
     * True only when the page is unmistakably the public store listing: it has no
     * join/leave/login form, no testing vocabulary, and shows at least two store-only
     * sections under a Google Play heading. Being strict here matters — a false
     * NO_PROGRAM hides a real beta for a full re-check TTL, and a genuine testing page
     * also links to the store, so a store link alone is not enough evidence.
     */
    private fun isGenericStoreDetailsPage(doc: Document, text: String): Boolean {
        val hasTestingAffordance = hasMarker(doc, "joinForm") ||
            hasMarker(doc, "leaveForm") ||
            hasMarker(doc, "gaia_loginform") ||
            hasMarker(doc, "identifierId") ||
            text.containsAny(TESTING_PHRASES)
        if (hasTestingAffordance) return false

        val storeSectionHits = STORE_PAGE_PHRASES.count { text.contains(it) }
        return text.contains("google play") && storeSectionHits >= 2
    }

    private fun String.containsAny(phrases: List<String>): Boolean =
        phrases.any { this.contains(it) }
}
