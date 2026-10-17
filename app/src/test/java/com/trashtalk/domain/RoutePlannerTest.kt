package com.trashtalk.domain

import com.trashtalk.core.LatLng
import com.trashtalk.data.model.Segment
import com.trashtalk.data.model.StreetClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlannerTest {

    private val origin = LatLng(29.5000, -98.4500)

    /** A segment [metresEast] east of the origin. */
    private fun at(
        id: String,
        metresEast: Double,
        surveyed: Boolean,
        pieces: Int = 5,
    ): Segment {
        // ~96 m per 0.001 degree of longitude at this latitude.
        val lng = origin.lng + metresEast / 96_000.0
        return Segment(
            id = id,
            name = id,
            streetClass = StreetClass.RESIDENTIAL,
            path = listOf(LatLng(origin.lat, lng), LatLng(origin.lat + 0.0004, lng)),
            lengthMeters = 45.0,
            estimatedPieces = pieces,
            surveyCount = if (surveyed) 3 else 0,
        )
    }

    @Test
    fun `unsurveyed focus prefers blocks nobody has walked`() {
        val segments = listOf(
            at("near-surveyed", 100.0, surveyed = true),
            at("far-unsurveyed", 300.0, surveyed = false),
        )
        val plan = RoutePlanner.plan(origin, segments, RoutePlanner.Focus.UNSURVEYED)

        // The unsurveyed one is further away and still wins, because the whole
        // point of the focus is to pull people onto blocks with no data.
        assertEquals(listOf("far-unsurveyed"), plan.targets.map { it.id })
        assertEquals(1, plan.coverageBonuses)
    }

    @Test
    fun `dirtiest focus trades distance for pieces`() {
        val segments = listOf(
            at("near-clean", 100.0, surveyed = true, pieces = 1),
            at("far-filthy", 600.0, surveyed = true, pieces = 60),
        )
        val plan = RoutePlanner.plan(origin, segments, RoutePlanner.Focus.DIRTIEST)

        assertEquals("far-filthy", plan.targets.first().id)
    }

    @Test
    fun `the plan stays inside its distance budget`() {
        val segments = (1..40).map { at("s$it", it * 120.0, surveyed = false) }
        val budget = 1_500.0
        val plan = RoutePlanner.plan(origin, segments, RoutePlanner.Focus.NEARBY, budget)

        // A loop that does not come home is not a loop. The planner has to
        // leave room for the return leg, so it must land under budget rather
        // than merely near it.
        assertTrue(
            "plan was ${plan.distanceMeters} m against a ${budget} m budget",
            plan.distanceMeters <= budget,
        )
        assertTrue("should still pick something", plan.targets.isNotEmpty())
    }

    @Test
    fun `an empty network plans nothing rather than crashing`() {
        val plan = RoutePlanner.plan(origin, emptyList(), RoutePlanner.Focus.UNSURVEYED)
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.estimatedPieces)
        assertEquals(0, plan.minutes)
    }

    @Test
    fun `estimated minutes assume a working pace, not a walking one`() {
        val segments = listOf(at("a", 200.0, surveyed = false))
        val plan = RoutePlanner.plan(origin, segments, RoutePlanner.Focus.NEARBY)

        // 3 km/h. At a brisk 5 km/h the same plan would read about 40% faster,
        // and send somebody out for twice the time they budgeted.
        val expected = ((plan.distanceMeters / 3000.0) * 60).toInt()
        assertTrue(kotlin.math.abs(plan.minutes - expected) <= 1)
    }
}
