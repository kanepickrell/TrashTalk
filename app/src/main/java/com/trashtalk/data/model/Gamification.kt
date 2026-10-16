package com.trashtalk.data.model

/**
 * The four roles a person can occupy in the TrashTalk ecosystem.
 *
 * The pairing is the point. Seekers and Eco-warriors engage each other by nature
 * (M1): both are competing on output, so they generate records, rivalry and sheer
 * volume. Scientists and Advocates work with each other by nature (M2): one
 * produces evidence, the other turns it into recruitment and pressure. M2 supplies
 * M1 with motivation and resources; M1 feeds M2 better data and more people. A
 * player is not picking a cosmetic class -- they are picking which half of that
 * engine they drive.
 */
enum class TrashPath(
    val id: String,
    val label: String,
    val tagline: String,
    val mission: String,
    val mechanism: String,
    val partner: String,
) {
    SCIENTIST(
        id = "scientist",
        label = "Scientist",
        tagline = "Find the pattern before anyone else does",
        mission = "Turn a neighbourhood into a dataset. You survey the blocks nobody " +
            "walks, guess where litter concentrates, and prove it with counts.",
        mechanism = "M2 with Advocates: you make the evidence, they make it matter.",
        partner = "Advocate",
    ),
    ECO_WARRIOR(
        id = "eco_warrior",
        label = "Eco-warrior",
        tagline = "Hold the line on the ground",
        mission = "Out-collect everyone and then keep the gains. You chase records, " +
            "defend adopted segments, and set the pace your team measures itself against.",
        mechanism = "M1 with Seekers: you two push each other, and the city gets cleaner.",
        partner = "Seeker",
    ),
    ADVOCATE(
        id = "advocate",
        label = "Advocate",
        tagline = "Turn evidence into demand",
        mission = "Carry the story outward. You recruit, teach, share the numbers, and " +
            "make local businesses hear that single-use packaging costs them customers.",
        mechanism = "M2 with Scientists: their data is your argument.",
        partner = "Scientist",
    ),
    SEEKER(
        id = "seeker",
        label = "Seeker",
        tagline = "Go further, every single day",
        mission = "Cover ground relentlessly. Streaks, distance, personal records -- you " +
            "are the reason coverage reaches the blocks the crowd never visits.",
        mechanism = "M1 with Eco-warriors: their records are the bar you came to clear.",
        partner = "Eco-warrior",
    );

    companion object {
        fun from(id: String?): TrashPath? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The quantity a badge watches. Keeping these as an enum rather than lambdas lets
 * the badge engine evaluate every badge against a plain stats snapshot, which is
 * what makes awarding them idempotent and replayable.
 */
enum class BadgeMetric {
    HOTSPOTS_CONFIRMED,
    FLAGS_PLANTED,
    SEGMENTS_FIRST_SURVEYED,
    IMAGES_LABELLED,
    PEER_RESURVEYS,

    CITY_TOP20_WEEKS,
    TEAM_LEAD_WEEKS,
    GUARDIAN_WEEKS_HELD,
    WEEKEND_RECORDS,
    BEST_HOUR_PIECES,

    SHARES,
    REFERRALS,
    TRIVIA_WEEKS,
    MOTIVATION_MESSAGES,
    INVITES_SENT,

    BEST_DAY_SEGMENTS,
    PERSONAL_BEST_HOUR,
    LONGEST_STREAK_DAYS,
    TEAM_STREAK_CROWN_DAYS,
    REMINDERS_ANSWERED,
}

/**
 * A badge with repeatable tiers. Twenty badges, five per path; each tier earned
 * counts once toward the path level, so a path never runs out of room -- the
 * seventh narrative level ends the story, and the paths continue indefinitely.
 */
data class BadgeDef(
    val id: String,
    val path: TrashPath,
    val name: String,
    val criteria: String,
    val howTo: String,
    val metric: BadgeMetric,
    val tierTargets: List<Int>,
) {
    val maxTier: Int get() = tierTargets.size

    /** How many tiers of this badge a given metric value has earned. */
    fun tiersEarned(value: Int): Int = tierTargets.count { value >= it }

    /** The next target to chase, or null when every tier is done. */
    fun nextTarget(value: Int): Int? = tierTargets.firstOrNull { value < it }

    fun tierLabel(tier: Int): String = when (tier) {
        1 -> "I"; 2 -> "II"; 3 -> "III"; 4 -> "IV"; 5 -> "V"
        else -> tier.toString()
    }
}

/**
 * The twenty badges. Names and criteria follow the original TrashTalk design; the
 * only addition is the Scientist's fifth, Calibration, which pays people to
 * re-walk segments somebody else logged. Without it nothing in the system rewards
 * checking another surveyor's work, and a dataset no one audits is not evidence.
 */
object BadgeCatalog {

    val all: List<BadgeDef> = listOf(
        // ---- Scientist: build the evidence base --------------------------------
        BadgeDef(
            id = "insight",
            path = TrashPath.SCIENTIST,
            name = "Insight",
            criteria = "Find a new hotspot",
            howTo = "Plant a hotspot flag, then prove it: log a collection there that " +
                "comes back heavy. The flag flips to confirmed and the hotspot is yours.",
            metric = BadgeMetric.HOTSPOTS_CONFIRMED,
            tierTargets = listOf(1, 3, 6, 12, 25),
        ),
        BadgeDef(
            id = "curiosity",
            path = TrashPath.SCIENTIST,
            name = "Curiosity",
            criteria = "Plant flags where you think litter collects",
            howTo = "Long-press the map to drop a guess. Being wrong is useful data too.",
            metric = BadgeMetric.FLAGS_PLANTED,
            tierTargets = listOf(3, 10, 25, 50, 100),
        ),
        BadgeDef(
            id = "neighborhood_exploration",
            path = TrashPath.SCIENTIST,
            name = "Neighborhood exploration",
            criteria = "Survey segments nobody has walked yet",
            howTo = "Unsurveyed segments render as dashed outlines. Every first survey " +
                "pays a coverage bonus on top of the pieces you collect.",
            metric = BadgeMetric.SEGMENTS_FIRST_SURVEYED,
            tierTargets = listOf(5, 15, 40, 80, 160),
        ),
        BadgeDef(
            id = "litter_analysis",
            path = TrashPath.SCIENTIST,
            name = "Litter analysis",
            criteria = "Label 100 images in a month",
            howTo = "Sort other collectors' photos into the survey taxonomy. Labels are " +
                "what turn a pile of photos into a Litter Impact Assessment.",
            metric = BadgeMetric.IMAGES_LABELLED,
            tierTargets = listOf(100, 250, 500, 1000, 2500),
        ),
        BadgeDef(
            id = "calibration",
            path = TrashPath.SCIENTIST,
            name = "Calibration",
            criteria = "Re-survey segments somebody else logged",
            howTo = "Walk a segment another collector recorded and log your own count. " +
                "Agreement raises the segment's confidence; disagreement flags it for review.",
            metric = BadgeMetric.PEER_RESURVEYS,
            tierTargets = listOf(5, 15, 35, 70, 150),
        ),

        // ---- Eco-warrior: win on the ground -----------------------------------
        BadgeDef(
            id = "city_leader",
            path = TrashPath.ECO_WARRIOR,
            name = "City leader",
            criteria = "Finish a week in your city's top 20, at your level",
            howTo = "Leaderboards reset Monday and are bracketed by level, so you compete " +
                "with people who have been at this as long as you have.",
            metric = BadgeMetric.CITY_TOP20_WEEKS,
            tierTargets = listOf(1, 4, 10, 20, 40),
        ),
        BadgeDef(
            id = "team_leader",
            path = TrashPath.ECO_WARRIOR,
            name = "Team leader",
            criteria = "Top your team's collection for a week",
            howTo = "Highest pieces collected on your team, Monday to Sunday.",
            metric = BadgeMetric.TEAM_LEAD_WEEKS,
            tierTargets = listOf(1, 4, 10, 20, 40),
        ),
        BadgeDef(
            id = "protect_the_flag",
            path = TrashPath.ECO_WARRIOR,
            name = "Protect the flag",
            criteria = "Adopt a segment and keep it under L2 for a week",
            howTo = "Adopt anonymously, then hold it: if anyone's survey puts your segment " +
                "at Moderate or worse, the run resets. Dirtier segments pay more.",
            metric = BadgeMetric.GUARDIAN_WEEKS_HELD,
            tierTargets = listOf(1, 3, 8, 16, 32),
        ),
        BadgeDef(
            id = "weekend_warrior",
            path = TrashPath.ECO_WARRIOR,
            name = "Weekend warrior",
            criteria = "Set your team's weekend collection record",
            howTo = "Saturday and Sunday only. The record to beat is on your team card.",
            metric = BadgeMetric.WEEKEND_RECORDS,
            tierTargets = listOf(1, 3, 7, 14, 28),
        ),
        BadgeDef(
            id = "raise_the_bar",
            path = TrashPath.ECO_WARRIOR,
            name = "Raise the bar",
            criteria = "Set a new record for most pieces collected in an hour",
            howTo = "Log a session with the timer running. Beating the standing city " +
                "record at your level takes the badge and the bragging rights.",
            metric = BadgeMetric.BEST_HOUR_PIECES,
            tierTargets = listOf(40, 80, 150, 250, 400),
        ),

        // ---- Advocate: convert evidence into demand ---------------------------
        BadgeDef(
            id = "sharing",
            path = TrashPath.ADVOCATE,
            name = "Sharing",
            criteria = "Share a statshot",
            howTo = "Your stats rendered as a card worth posting. Numbers travel further " +
                "than opinions.",
            metric = BadgeMetric.SHARES,
            tierTargets = listOf(1, 5, 15, 40, 100),
        ),
        BadgeDef(
            id = "recruiting",
            path = TrashPath.ADVOCATE,
            name = "Recruiting",
            criteria = "Bring someone new into TrashTalk",
            howTo = "They join on your referral code and log their first collection.",
            metric = BadgeMetric.REFERRALS,
            tierTargets = listOf(1, 3, 8, 20, 50),
        ),
        BadgeDef(
            id = "education",
            path = TrashPath.ADVOCATE,
            name = "Education",
            criteria = "Complete the weekly trivia",
            howTo = "Five questions a week on waste, materials and the circular economy. " +
                "You cannot argue the case if you do not know it.",
            metric = BadgeMetric.TRIVIA_WEEKS,
            tierTargets = listOf(1, 4, 12, 26, 52),
        ),
        BadgeDef(
            id = "motivation",
            path = TrashPath.ADVOCATE,
            name = "Motivation",
            criteria = "Send your team a push, weekly for a month",
            howTo = "One message a week, four weeks running. Teams with an Advocate " +
                "outlast teams without one.",
            metric = BadgeMetric.MOTIVATION_MESSAGES,
            tierTargets = listOf(4, 12, 26, 52, 104),
        ),
        BadgeDef(
            id = "inspiration",
            path = TrashPath.ADVOCATE,
            name = "Inspiration",
            criteria = "Invite three people a month to the movement",
            howTo = "An invite is not a referral -- it counts whether or not they join. " +
                "Ask anyway.",
            metric = BadgeMetric.INVITES_SENT,
            tierTargets = listOf(3, 12, 36, 72, 150),
        ),

        // ---- Seeker: cover the ground nobody covers ---------------------------
        BadgeDef(
            id = "long_distance",
            path = TrashPath.SEEKER,
            name = "Long distance",
            criteria = "Set a record for segments surveyed in a day",
            howTo = "Distinct segments, midnight to midnight.",
            metric = BadgeMetric.BEST_DAY_SEGMENTS,
            tierTargets = listOf(5, 12, 25, 45, 80),
        ),
        BadgeDef(
            id = "new_personal_record",
            path = TrashPath.SEEKER,
            name = "New personal record",
            criteria = "Beat your own best hour",
            howTo = "Only your own record counts here -- this one is you against you.",
            metric = BadgeMetric.PERSONAL_BEST_HOUR,
            tierTargets = listOf(25, 60, 120, 200, 320),
        ),
        BadgeDef(
            id = "personal_streak",
            path = TrashPath.SEEKER,
            name = "Personal streak",
            criteria = "Collect at least one piece a day for a month",
            howTo = "One piece counts. The habit is the point, not the volume.",
            metric = BadgeMetric.LONGEST_STREAK_DAYS,
            tierTargets = listOf(30, 60, 100, 200, 365),
        ),
        BadgeDef(
            id = "longest_streak",
            path = TrashPath.SEEKER,
            name = "Longest streak",
            criteria = "Hold the longest active streak on your team",
            howTo = "Measured in days while you hold the crown.",
            metric = BadgeMetric.TEAM_STREAK_CROWN_DAYS,
            tierTargets = listOf(7, 21, 50, 100, 200),
        ),
        BadgeDef(
            id = "diligence",
            path = TrashPath.SEEKER,
            name = "Diligence",
            criteria = "Answer every collection reminder in a week",
            howTo = "Reminders fire where you already walk. Diligence is the quiet badge " +
                "that makes the others possible.",
            metric = BadgeMetric.REMINDERS_ANSWERED,
            tierTargets = listOf(7, 21, 50, 120, 250),
        ),
    )

    val byId: Map<String, BadgeDef> = all.associateBy { it.id }

    fun forPath(path: TrashPath): List<BadgeDef> = all.filter { it.path == path }

    /** Tiers needed per path level. Five tiers is one level, as in the original design. */
    const val TIERS_PER_PATH_LEVEL = 5
}

/**
 * Everything the badge engine counts. One flat snapshot so badge evaluation is a
 * pure function of stats, never of event ordering.
 */
data class UserStats(
    val pieces: Int = 0,
    val collections: Int = 0,
    val grams: Double = 0.0,
    val minutes: Int = 0,
    val segmentsTouched: Set<String> = emptySet(),

    val hotspotsConfirmed: Int = 0,
    val flagsPlanted: Int = 0,
    val segmentsFirstSurveyed: Int = 0,
    val imagesLabelled: Int = 0,
    val peerResurveys: Int = 0,

    val cityTop20Weeks: Int = 0,
    val teamLeadWeeks: Int = 0,
    val guardianWeeksHeld: Int = 0,
    val weekendRecords: Int = 0,
    val bestHourPieces: Int = 0,

    val shares: Int = 0,
    val referrals: Int = 0,
    val triviaWeeks: Int = 0,
    val motivationMessages: Int = 0,
    val invitesSent: Int = 0,

    val bestDaySegments: Int = 0,
    val personalBestHour: Int = 0,
    val longestStreakDays: Int = 0,
    val teamStreakCrownDays: Int = 0,
    val remindersAnswered: Int = 0,
) {
    fun metric(m: BadgeMetric): Int = when (m) {
        BadgeMetric.HOTSPOTS_CONFIRMED -> hotspotsConfirmed
        BadgeMetric.FLAGS_PLANTED -> flagsPlanted
        BadgeMetric.SEGMENTS_FIRST_SURVEYED -> segmentsFirstSurveyed
        BadgeMetric.IMAGES_LABELLED -> imagesLabelled
        BadgeMetric.PEER_RESURVEYS -> peerResurveys
        BadgeMetric.CITY_TOP20_WEEKS -> cityTop20Weeks
        BadgeMetric.TEAM_LEAD_WEEKS -> teamLeadWeeks
        BadgeMetric.GUARDIAN_WEEKS_HELD -> guardianWeeksHeld
        BadgeMetric.WEEKEND_RECORDS -> weekendRecords
        BadgeMetric.BEST_HOUR_PIECES -> bestHourPieces
        BadgeMetric.SHARES -> shares
        BadgeMetric.REFERRALS -> referrals
        BadgeMetric.TRIVIA_WEEKS -> triviaWeeks
        BadgeMetric.MOTIVATION_MESSAGES -> motivationMessages
        BadgeMetric.INVITES_SENT -> invitesSent
        BadgeMetric.BEST_DAY_SEGMENTS -> bestDaySegments
        BadgeMetric.PERSONAL_BEST_HOUR -> personalBestHour
        BadgeMetric.LONGEST_STREAK_DAYS -> longestStreakDays
        BadgeMetric.TEAM_STREAK_CROWN_DAYS -> teamStreakCrownDays
        BadgeMetric.REMINDERS_ANSWERED -> remindersAnswered
    }
}

/** A badge tier the player has actually banked. */
data class EarnedBadge(
    val badgeId: String = "",
    val tier: Int = 0,
    val earnedAt: Long = 0L,
)

data class UserProfile(
    val id: String = "",
    val displayName: String = "",
    val cityId: String = "san-antonio-tx",
    val teamId: String? = null,
    val teamName: String? = null,
    val createdAt: Long = 0L,

    val totalPoints: Int = 0,
    /** Narrative progress: the highest of the seven manifesto levels completed. */
    val narrativeLevel: Int = 0,
    val completedLevels: Set<Int> = emptySet(),
    /** Chosen after the narrative ends; null until then. */
    val path: TrashPath? = null,
    /** Tiers banked per badge id. */
    val badgeTiers: Map<String, Int> = emptyMap(),
    val earned: List<EarnedBadge> = emptyList(),
    val stats: UserStats = UserStats(),

    val streakDays: Int = 0,
    val lastCollectionDay: Long = 0L,
    val referralCode: String = "",
) {
    /** Narrative levels run 1..7; a player is "Level n" until they finish level n. */
    val displayLevel: Int get() = (narrativeLevel + 1).coerceAtMost(7)

    val narrativeComplete: Boolean get() = narrativeLevel >= 7

    fun tiersIn(p: TrashPath): Int =
        BadgeCatalog.forPath(p).sumOf { badgeTiers[it.id] ?: 0 }

    /** Level within a path: five banked tiers per level, unbounded above. */
    fun pathLevel(p: TrashPath): Int = 1 + tiersIn(p) / BadgeCatalog.TIERS_PER_PATH_LEVEL

    fun tiersToNextPathLevel(p: TrashPath): Int =
        BadgeCatalog.TIERS_PER_PATH_LEVEL - (tiersIn(p) % BadgeCatalog.TIERS_PER_PATH_LEVEL)

    val totalTiers: Int get() = badgeTiers.values.sum()

    /**
     * The title shown everywhere: "Level 3 Advocate" once a path is chosen, and the
     * narrative rank before that.
     */
    val title: String
        get() = path?.let { "Level ${pathLevel(it)} ${it.label}" }
            ?: "Level $displayLevel Newcomer"
}
