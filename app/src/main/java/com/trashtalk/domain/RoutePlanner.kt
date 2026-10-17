package com.trashtalk.domain

import com.trashtalk.core.LatLng
import com.trashtalk.core.haversineMeters
import com.trashtalk.data.model.Segment
import kotlin.math.roundToInt

/**
 * Answers "where should I walk today".
 *
 * The planner picks target segments and orders them into a walkable loop. It
 * does not route: turn-by-turn along real pavement needs a routing engine, and
 * until there is one this returns the targets in the order a person would
 * sensibly take them, which is the part that actually changes where somebody
 * goes. A line between two blocks is not worth pretending to be a route.
 */
object RoutePlanner {

    /** What the walker wants out of the next hour. */
    enum class Focus(val label: String, val caption: String) {
        UNSURVEYED("Unsurveyed", "Blocks nobody has walked yet - worth a coverage bonus each"),
        DIRTIEST("Dirtiest", "The worst blocks within reach"),
        NEARBY("Closest", "Whatever is nearest, for a short walk"),
    }

    /** A planned walk. */
    data class Plan(
        val focus: Focus,
        val targets: List<Segment>,
        /** Metres along the route, including the walk between targets. */
        val distanceMeters: Double,
        /** Coverage bonuses available, i.e. targets never surveyed. */
        val coverageBonuses: Int,
        /** Pieces the model expects to be there, summed over the targets. */
        val estimatedPieces: Int,
    ) {
        val isEmpty: Boolean get() = targets.isEmpty()

        /**
         * Minutes, at a working pace.
         *
         * 3 km/h rather than a walking 5: somebody stopping every few metres to
         * pick something up is not walking, and a plan that assumes they are
         * sends them out for twice the time they budgeted.
         */
        val minutes: Int get() = ((distanceMeters / 3000.0) * 60).roundToInt()
    }

    /**
     * Builds a plan from [from], within a rough [budgetMeters].
     *
     * Greedy nearest-neighbour from the start, taking the best-scoring target
     * still in reach. Not optimal -- the travelling-salesman problem is not
     * worth solving for a dozen blocks -- but it produces the loop a person
     * would walk, which is the bar.
     */
    fun plan(
        from: LatLng,
        segments: List<Segment>,
        focus: Focus,
        budgetMeters: Double = 2_000.0,
    ): Plan {
        val candidates = segments
            .filter { it.path.isNotEmpty() }
            .filter { focus.admits(it) }
            .toMutableList()

        val targets = ArrayList<Segment>()
        var at = from
        var spent = 0.0

        while (candidates.isNotEmpty()) {
            val next = candidates.minByOrNull { it.cost(at, focus) } ?: break
            val travel = haversineMeters(at, next.midpoint)
            val leg = travel + next.lengthMeters
            // The return trip has to fit too, or the plan is a one-way walk
            // dressed up as a loop.
            val home = haversineMeters(next.midpoint, from)
            if (spent + leg + home > budgetMeters && targets.isNotEmpty()) break

            targets += next
            candidates -= next
            spent += leg
            at = next.midpoint
        }

        return Plan(
            focus = focus,
            targets = targets,
            distanceMeters = spent + if (targets.isEmpty()) 0.0 else haversineMeters(at, from),
            coverageBonuses = targets.count { !it.hasBeenSurveyed },
            estimatedPieces = targets.sumOf { it.estimatedPieces },
        )
    }

    private fun Focus.admits(segment: Segment): Boolean = when (this) {
        Focus.UNSURVEYED -> !segment.hasBeenSurveyed
        Focus.DIRTIEST -> segment.estimatedPieces > 0
        Focus.NEARBY -> true
    }

    /**
     * How undesirable a target is from here: lower is better.
     *
     * Distance is in metres and the focus term is scaled to metres too, so the
     * two are actually comparable rather than one quietly dominating because it
     * happens to be measured in bigger numbers.
     */
    private fun Segment.cost(from: LatLng, focus: Focus): Double {
        val away = haversineMeters(from, midpoint)
        return when (focus) {
            // 400 m of detour is worth a coverage bonus; much more and the
            // walker spends the hour travelling rather than surveying.
            Focus.UNSURVEYED -> away - if (!hasBeenSurveyed) 400.0 else 0.0
            // Each expected piece is worth about 30 m of walking.
            Focus.DIRTIEST -> away - estimatedPieces * 30.0
            Focus.NEARBY -> away
        }
    }
}
