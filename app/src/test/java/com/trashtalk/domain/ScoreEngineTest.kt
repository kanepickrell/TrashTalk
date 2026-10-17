package com.trashtalk.domain

import com.trashtalk.data.model.LitterType
import com.trashtalk.data.model.Segment
import com.trashtalk.data.model.StreetClass
import com.trashtalk.data.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scoring rules, which are a statement about what the project wants.
 *
 * These assert the incentives rather than specific totals: a total is a
 * number somebody may reasonably retune, but "a plastic bag beats a
 * newspaper" is the design, and a change to that should have to be deliberate
 * enough to break a test.
 */
class ScoreEngineTest {

    private fun segment(
        litterIndex: Float = 0.5f,
        surveyCount: Int = 3,
        pieces: Int = 20,
    ) = Segment(
        id = "s_test",
        name = "Test Street",
        streetClass = StreetClass.RESIDENTIAL,
        litterIndex = litterIndex,
        estimatedPieces = pieces,
        surveyCount = surveyCount,
        lengthMeters = 200.0,
    )

    private fun draft(counts: Map<LitterType, Int>) = CollectionDraft(
        segmentId = "s_test",
        segmentName = "Test Street",
        lat = 29.5,
        lng = -98.45,
        counts = counts,
    )

    @Test
    fun `harm outranks mass`() {
        val profile = UserProfile(id = "u1")
        val seg = segment()

        // A plastic bag is 5.5 g; a sheet of paper is 18 g and three times
        // heavier. The bag must still score higher, because the bag is what
        // ends up in an animal.
        val bag = ScoreEngine.score(draft(mapOf(LitterType.PLASTIC_BAG to 1)), seg, profile)
        val paper = ScoreEngine.score(draft(mapOf(LitterType.PAPER to 1)), seg, profile)

        assertTrue(
            "a plastic bag (${bag.total}) should outscore paper (${paper.total})",
            bag.total > paper.total,
        )
    }

    @Test
    fun `a first survey pays the coverage bonus, a repeat does not`() {
        val profile = UserProfile(id = "u1")
        val counts = mapOf(LitterType.CIGARETTE to 4)

        val first = ScoreEngine.score(draft(counts), segment(surveyCount = 0), profile)
        val repeat = ScoreEngine.score(draft(counts), segment(surveyCount = 5), profile)

        assertTrue("first survey should pay a coverage bonus", first.coverageBonus > 0)
        assertEquals("a repeat survey should not", 0, repeat.coverageBonus)
        assertTrue(first.total > repeat.total)
    }

    @Test
    fun `a dirtier segment is worth more than a clean one for the same haul`() {
        val profile = UserProfile(id = "u1")
        val counts = mapOf(LitterType.FOOD_PACKAGING to 5)

        val filthy = ScoreEngine.score(draft(counts), segment(litterIndex = 0.9f), profile)
        val clean = ScoreEngine.score(draft(counts), segment(litterIndex = 0.05f), profile)

        assertTrue(
            "difficulty should reward the harder block (${filthy.total} vs ${clean.total})",
            filthy.total > clean.total,
        )
    }

    @Test
    fun `an empty pickup scores nothing`() {
        val result = ScoreEngine.score(draft(emptyMap()), segment(), UserProfile(id = "u1"))
        assertEquals(0, result.total)
    }

    @Test
    fun `the breakdown adds up to the total`() {
        val result = ScoreEngine.score(
            draft(mapOf(LitterType.PLASTIC_BOTTLE to 3, LitterType.CIGARETTE to 7)),
            segment(surveyCount = 0),
            UserProfile(id = "u1"),
        )
        // The breakdown is shown to the user as the reason for the award. If
        // the parts do not sum to the whole, the explanation is a lie.
        val parts = result.basePoints + result.difficultyBonus + result.streakBonus +
            result.coverageBonus + result.harmBonus
        assertEquals(result.total, parts)
    }
}
