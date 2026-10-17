package com.trashtalk.domain

import com.trashtalk.data.model.LitterType
import com.trashtalk.data.model.Segment
import com.trashtalk.data.model.StreetClass
import com.trashtalk.data.model.UserProfile
import org.junit.Test
import java.io.File

/**
 * Emits the fixtures the TypeScript port is pinned against.
 *
 * The server runs a port of ScoreEngine, and the client keeps running this
 * one so a submission can show a number immediately instead of waiting on a
 * function. Two live implementations of the same rules drift, and the drift
 * surfaces to a user as an award that changes after the celebration has
 * played.
 *
 * So rather than hand-copying expected values into the TypeScript tests --
 * where a transcription slip would read exactly like agreement -- this writes
 * them from the real engine. Run it, commit the JSON, and the port is checked
 * against the thing it was ported from.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*ScoringParityFixtures*'
 */
class ScoringParityFixtures {

    private fun segment(litterIndex: Float, surveyCount: Int) = Segment(
        id = "s_fixture",
        name = "Fixture Street",
        streetClass = StreetClass.RESIDENTIAL,
        litterIndex = litterIndex,
        estimatedPieces = 20,
        surveyCount = surveyCount,
        lengthMeters = 200.0,
    )

    @Test
    fun `write parity fixtures`() {
        data class Case(
            val name: String,
            val counts: Map<LitterType, Int>,
            val litterIndex: Float,
            val streakDays: Int,
            val firstSurvey: Boolean,
        )

        val cases = listOf(
            Case("empty", emptyMap(), 0.5f, 0, false),
            Case("single cigarette", mapOf(LitterType.CIGARETTE to 1), 0f, 0, false),
            Case("one of each plastic",
                mapOf(
                    LitterType.PLASTIC_BAG to 1, LitterType.FILM_WRAPPER to 1,
                    LitterType.FOAM to 1, LitterType.FOOD_PACKAGING to 1,
                    LitterType.PLASTIC_BOTTLE to 1,
                ), 0f, 0, false),
            Case("mixed haul, dirty block",
                mapOf(
                    LitterType.CIGARETTE to 7, LitterType.PLASTIC_BOTTLE to 3,
                    LitterType.PAPER to 2, LitterType.GLASS to 1,
                ), 0.9f, 0, false),
            Case("first survey", mapOf(LitterType.FOOD_PACKAGING to 5), 0.3f, 0, true),
            Case("long streak", mapOf(LitterType.ALUMINUM_CAN to 10), 0.45f, 12, false),
            Case("capped streak", mapOf(LitterType.ALUMINUM_CAN to 10), 0.45f, 99, false),
            Case("everything at once",
                mapOf(
                    LitterType.PLASTIC_BAG to 4, LitterType.CIGARETTE to 21,
                    LitterType.OTHER to 3,
                ), 0.73f, 9, true),
        )

        val rows = cases.map { c ->
            val draft = CollectionDraft(
                segmentId = "s_fixture",
                segmentName = "Fixture Street",
                lat = 29.5, lng = -98.45,
                counts = c.counts,
            )
            val b = ScoreEngine.score(
                draft,
                segment(c.litterIndex, if (c.firstSurvey) 0 else 3),
                UserProfile(id = "u", streakDays = c.streakDays),
            )
            """    {
      "name": ${q(c.name)},
      "counts": {${c.counts.entries.joinToString(", ") { "${q(it.key.name)}: ${it.value}" }}},
      "litterIndex": ${c.litterIndex},
      "streakDays": ${c.streakDays},
      "isFirstSurvey": ${c.firstSurvey},
      "expected": {
        "basePoints": ${b.basePoints},
        "difficultyBonus": ${b.difficultyBonus},
        "streakBonus": ${b.streakBonus},
        "coverageBonus": ${b.coverageBonus},
        "harmBonus": ${b.harmBonus},
        "total": ${b.total}
      }
    }"""
        }

        val json = "{\n  \"generatedFrom\": \"ScoreEngine.kt\",\n  \"cases\": [\n" +
            rows.joinToString(",\n") + "\n  ]\n}\n"

        val out = File("../functions/src/scoring.fixtures.json")
        out.parentFile?.mkdirs()
        out.writeText(json)
        println("wrote ${out.absolutePath}")
    }

    private fun q(s: String) = "\"" + s.replace("\"", "\\\"") + "\""
}
