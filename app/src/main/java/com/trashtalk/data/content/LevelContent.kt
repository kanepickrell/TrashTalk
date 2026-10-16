package com.trashtalk.data.content

/**
 * The first seven levels are a manifesto, not a tutorial.
 *
 * They run in order and argue a single case: litter is not an aesthetic problem,
 * it is the visible end of a linear materials economy; the concept of "garbage"
 * is a recent human invention; the usual escapes (biodegradable, compostable,
 * recycling) mostly do not work; and the way out is to stop producing waste rather
 * than to manage it better. Only after level seven does a player choose a path --
 * by then they know what they are choosing.
 *
 * Figures carry their sources inline. Where a number is a projection or an
 * estimate it says so, because an Advocate who overstates the case loses the
 * argument the first time somebody checks.
 */

data class LevelSection(
    val heading: String,
    val body: String,
)

data class KeyStat(
    val value: String,
    val caption: String,
    val source: String,
)

data class QuizQuestion(
    val prompt: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
)

data class NarrativeLevel(
    val index: Int,
    val eyebrow: String,
    val title: String,
    val question: String,
    val standfirst: String,
    val sections: List<LevelSection>,
    val stats: List<KeyStat> = emptyList(),
    val quiz: List<QuizQuestion> = emptyList(),
    val action: String,
    val pointsAward: Int = 120,
)

object LevelContent {

    val levels: List<NarrativeLevel> = listOf(

        // ------------------------------------------------------------------ 1
        NarrativeLevel(
            index = 1,
            eyebrow = "Level 1",
            title = "Why street litter is not a small problem",
            question = "Why is street litter such a problem?",
            standfirst = "A wrapper on a kerb is not where the story ends. It is where " +
                "it starts, and the street is the last place anyone can still stop it.",
            sections = listOf(
                LevelSection(
                    heading = "The street is upstream of the ocean",
                    body = "Litter in our streets enables and compounds much larger " +
                        "problems. Storm drains are not filters -- they are a delivery " +
                        "system. What sits on a kerb in San Antonio travels through a " +
                        "drain, into a creek, down a river and out to sea. Roughly 8 " +
                        "million tonnes of plastic and other trash reach the ocean every " +
                        "year, and the majority of marine debris begins on land as " +
                        "ordinary mismanaged litter.",
                ),
                LevelSection(
                    heading = "A plastic bag does not rot",
                    body = "A banana peel is consumed by a crowd of hungry microbes. A " +
                        "plastic bag has no such predator. It photodegrades: sunlight " +
                        "breaks it into smaller and smaller fragments, and those " +
                        "fragments are deadly when swallowed. They also carry " +
                        "pollutants, including polychlorinated biphenyls (PCBs) and " +
                        "endocrine disrupters. Worse, they look like food -- drifting " +
                        "film resembles zooplankton and jellyfish, and animals eat it " +
                        "on purpose.",
                ),
                LevelSection(
                    heading = "The story does not end with a dead jellyfish",
                    body = "The UN Environment Programme has estimated tens of " +
                        "thousands of plastic pieces floating in every square mile of " +
                        "ocean. After damaging the food web, that material can come back " +
                        "to the table: a UNEP and World Health Organization review " +
                        "identified more than 800 man-made chemicals, bisphenol A (BPA) " +
                        "among them, in products and materials people encounter daily. " +
                        "The litter you walk past is not somewhere else's problem in " +
                        "either direction.",
                ),
            ),
            stats = listOf(
                KeyStat("8M tonnes", "plastic and trash entering the ocean each year", "UNEP"),
                KeyStat("~60%", "of ocean pollution originating as human litter", "UNEP"),
                KeyStat("46,000", "plastic pieces estimated per square mile of ocean", "UNEP"),
                KeyStat("800+", "man-made chemicals found in everyday products", "UNEP / WHO"),
            ),
            quiz = listOf(
                QuizQuestion(
                    prompt = "What happens to a plastic bag left in the sun?",
                    options = listOf(
                        "Microbes digest it the way they digest food waste",
                        "It photodegrades into smaller fragments that persist",
                        "It dissolves harmlessly in rainwater",
                    ),
                    correctIndex = 1,
                    explanation = "Photodegradation makes the problem smaller and more " +
                        "numerous, not smaller and gone.",
                ),
                QuizQuestion(
                    prompt = "Where does most ocean plastic come from?",
                    options = listOf(
                        "Ships and offshore platforms",
                        "Natural erosion of the seabed",
                        "Land-based litter carried by drains and rivers",
                    ),
                    correctIndex = 2,
                    explanation = "Which is exactly why a street-level map is worth building.",
                ),
            ),
            action = "Look at your own block on the map. Note the one spot you would bet " +
                "collects litter -- you will plant a flag there soon enough.",
        ),

        // ------------------------------------------------------------------ 2
        NarrativeLevel(
            index = 2,
            eyebrow = "Level 2",
            title = "How we got here",
            question = "How did we get here?",
            standfirst = "Nobody decided to build a disposable society. It was assembled " +
                "out of a thousand small conveniences, each one reasonable on its own.",
            sections = listOf(
                LevelSection(
                    heading = "Disposability was sold as progress",
                    body = "We live in a modern disposable society, and we did not " +
                        "arrive here by accident. Single-use packaging solved real " +
                        "problems: it kept food safe for longer, cut shipping weight, " +
                        "lowered prices and freed people from washing up. Convenience " +
                        "and affordability are not stupid things to want. The cost was " +
                        "simply moved somewhere nobody was looking -- into the future, " +
                        "and onto the kerb.",
                ),
                LevelSection(
                    heading = "The container became the product",
                    body = "Somewhere along the way the package stopped being how goods " +
                        "travelled and became part of what was sold: brighter, lighter, " +
                        "more layers, more laminates. Each improvement made the package " +
                        "better at marketing and worse at ever being anything again. A " +
                        "multi-layer film pouch is a triumph of engineering and a dead " +
                        "end as a material.",
                ),
                LevelSection(
                    heading = "The system now expects litter",
                    body = "Once disposability is assumed, litter stops being an " +
                        "anomaly and becomes a running cost -- absorbed by city budgets, " +
                        "volunteers and the places furthest from anyone with influence. " +
                        "Litter is not a moral failure distributed randomly across a " +
                        "population. It is the predictable output of a system, and it " +
                        "lands unevenly.",
                ),
            ),
            stats = listOf(
                KeyStat("1950s", "single-use packaging enters the mainstream", "Historical"),
                KeyStat("Linear", "take, make, dispose -- the model still in force", "Circular economy"),
            ),
            quiz = listOf(
                QuizQuestion(
                    prompt = "Why did single-use packaging spread so quickly?",
                    options = listOf(
                        "It was cheaper and more convenient, and the costs were deferred",
                        "Consumers asked for more waste",
                        "Regulators required disposable materials",
                    ),
                    correctIndex = 0,
                    explanation = "Understanding the appeal matters: you cannot argue " +
                        "people out of a convenience without offering one back.",
                ),
            ),
            action = "Count the single-use items you touched today. Not to feel guilty -- " +
                "to know the number.",
        ),

        // ------------------------------------------------------------------ 3
        NarrativeLevel(
            index = 3,
            eyebrow = "Level 3",
            title = "What garbage actually is",
            question = "What is garbage?",
            standfirst = "Garbage does not exist in nature. It is a human invention, and " +
                "a surprisingly recent one.",
            sections = listOf(
                LevelSection(
                    heading = "In nature, every output is an input",
                    body = "Waste in a functioning ecosystem is food, shelter or soil " +
                        "for something else. A fallen tree is an apartment block. A " +
                        "carcass is a season of meals. Nothing accumulates, because " +
                        "nothing is produced that nothing else can use. That is not " +
                        "sentimentality, it is just what a closed loop looks like after " +
                        "a few billion years of iteration.",
                ),
                LevelSection(
                    heading = "Synthetic materials broke the loop",
                    body = "Human-made materials severed that harmony by arriving faster " +
                        "than anything evolved to eat them. The result is visible every " +
                        "time an animal gets it wrong: a dog swallowing a plastic object " +
                        "it took for food, a cat chewing an extension cord. In nature " +
                        "all outputs are useful -- a natural wisdom worth echoing, but " +
                        "not in the innocent ignorance that leads unwitting creatures to " +
                        "eat inedible trash.",
                ),
                LevelSection(
                    heading = "So when did garbage begin?",
                    body = "Most accounts put the modern concept in the 1950s, with the " +
                        "rise of single-use packaging and polymers that offered " +
                        "convenience and affordability at a price nobody was asked to " +
                        "pay up front. Before that, materials were too valuable to " +
                        "throw away. Garbage is not an eternal feature of human life. It " +
                        "is about seventy years old, which means it is young enough to " +
                        "end.",
                ),
            ),
            stats = listOf(
                KeyStat("Zero", "waste produced by a closed natural system", "Ecology"),
                KeyStat("~70 yrs", "age of garbage as a mass phenomenon", "Historical"),
            ),
            quiz = listOf(
                QuizQuestion(
                    prompt = "Why is there no 'garbage' in a natural ecosystem?",
                    options = listOf(
                        "Nature produces very little material",
                        "Every output is an input to something else",
                        "Decomposers can break down any material at all",
                    ),
                    correctIndex = 1,
                    explanation = "The loop is the mechanism. Synthetics broke it by " +
                        "outpacing anything that could consume them.",
                ),
                QuizQuestion(
                    prompt = "When did garbage emerge as a mass phenomenon?",
                    options = listOf("The 1750s", "The 1950s", "The 1990s"),
                    correctIndex = 1,
                    explanation = "Single-use packaging and cheap polymers made " +
                        "throwing things away normal.",
                ),
            ),
            action = "Pick one object near you and ask what it becomes next. If the answer " +
                "is 'nothing', you have found the problem.",
        ),

        // ------------------------------------------------------------------ 4
        NarrativeLevel(
            index = 4,
            eyebrow = "Level 4",
            title = "The alternatives, honestly assessed",
            question = "Are there alternatives?",
            standfirst = "Biodegradable, compostable, recyclable. Three words doing an " +
                "enormous amount of reassuring and not much else.",
            sections = listOf(
                LevelSection(
                    heading = "Biodegradable means almost nothing on its own",
                    body = "Every material biodegrades eventually; the label omits the " +
                        "two facts that matter -- how long, and under what conditions. " +
                        "A plastic certified biodegradable in a 58 C industrial vessel " +
                        "behaves like ordinary plastic in a creek, a landfill or a " +
                        "gutter. On a street, 'biodegradable' is a claim about a " +
                        "laboratory, not about the kerb it is lying on.",
                ),
                LevelSection(
                    heading = "Compostable needs a composter",
                    body = "Compostable packaging is a real improvement -- where " +
                        "industrial composting exists, accepts it, and the item actually " +
                        "gets there. Absent that chain it is worse than useless: it " +
                        "contaminates recycling streams, confuses people acting in good " +
                        "faith, and still litters. Infrastructure is the product. The " +
                        "cup is just a cup.",
                ),
                LevelSection(
                    heading = "Recycling is a real process with real limits",
                    body = "Recycling genuinely works for a few materials -- aluminium " +
                        "and glass can cycle almost indefinitely, and that is worth " +
                        "defending. Plastics mostly downcycle: each pass shortens the " +
                        "polymer chains, so a bottle becomes fibre, then filler, then " +
                        "waste. Sorting costs money, mixed and multi-layer materials " +
                        "cannot be separated economically, and markets for low-grade " +
                        "bales collapse. Recycling is a backstop that was sold as an " +
                        "absolution.",
                ),
                LevelSection(
                    heading = "Which leaves the only reliable move",
                    body = "Not generating the material in the first place. Every " +
                        "alternative above is an attempt to make disposal less bad. The " +
                        "one intervention that always works is the item that was never " +
                        "made.",
                ),
            ),
            stats = listOf(
                KeyStat("Infinite", "practical recycling passes for aluminium and glass", "Materials science"),
                KeyStat("Downcycle", "what most plastic recycling actually does", "Materials science"),
                KeyStat("58 C", "the industrial conditions 'biodegradable' often assumes", "Standards"),
            ),
            quiz = listOf(
                QuizQuestion(
                    prompt = "A cup is labelled 'biodegradable'. On a street, that means:",
                    options = listOf(
                        "It will break down within weeks",
                        "Very little -- the label implies conditions the street does not provide",
                        "It is safe to litter",
                    ),
                    correctIndex = 1,
                    explanation = "Time and conditions are the whole claim, and both are " +
                        "usually missing from the label.",
                ),
                QuizQuestion(
                    prompt = "Which material genuinely cycles almost indefinitely?",
                    options = listOf("PET plastic", "Multi-layer film", "Aluminium"),
                    correctIndex = 2,
                    explanation = "Which is why an aluminium can scores low on harm in " +
                        "this app, and a film wrapper scores near the top.",
                ),
            ),
            action = "Open the survey taxonomy and read the harm notes. They are the " +
                "reason points are not equal per piece.",
        ),

        // ------------------------------------------------------------------ 5
        NarrativeLevel(
            index = 5,
            eyebrow = "Level 5",
            title = "A plan for the future",
            question = "What would actually fix this?",
            standfirst = "To outsmart the garbage crisis we have to eliminate the idea of " +
                "waste -- not improve how we bury it.",
            sections = listOf(
                LevelSection(
                    heading = "Shift what we buy for, slightly",
                    body = "Imagine our needs and desires moved just a little: we look " +
                        "for products not only for their contents but for the experience " +
                        "of owning them. Packaging designed for quality and longevity, " +
                        "that ages beautifully instead of becoming embarrassing the " +
                        "moment the contents are gone. A jar worth keeping is not " +
                        "packaging; it is an object.",
                ),
                LevelSection(
                    heading = "Price waste the way nature does: as value",
                    body = "Instead of seeing waste purely as a negative -- a useless " +
                        "by-product we spend money to burn or bury -- we can see it as a " +
                        "positive: an inherently valuable combination of materials that " +
                        "can be processed and shaped into objects with specific " +
                        "purposes. Nothing changes until the balance sheet agrees that a " +
                        "discarded material is an asset.",
                ),
                LevelSection(
                    heading = "A stage, not an ending",
                    body = "The key is to see our outputs not as problems but as " +
                        "assets; to see waste not as the end of a linear process but as " +
                        "a stage in a circular life cycle. That is the whole shift. " +
                        "Everything else -- deposit schemes, refill systems, extended " +
                        "producer responsibility, repair rights -- is implementation " +
                        "detail following from it.",
                ),
            ),
            stats = listOf(
                KeyStat("Circular", "waste as a stage, not an endpoint", "Circular economy"),
                KeyStat("Design", "where ~80% of a product's impact is decided", "Design research"),
            ),
            quiz = listOf(
                QuizQuestion(
                    prompt = "What is the core shift this level argues for?",
                    options = listOf(
                        "Better landfills and cleaner incinerators",
                        "Treating outputs as assets in a circular life cycle",
                        "Asking individuals to try harder at sorting",
                    ),
                    correctIndex = 1,
                    explanation = "Manage waste better and you still have waste. Redefine " +
                        "it as material and the problem changes shape.",
                ),
            ),
            action = "Name one package in your home you would have kept if it had been " +
                "designed to be kept.",
        ),

        // ------------------------------------------------------------------ 6
        NarrativeLevel(
            index = 6,
            eyebrow = "Level 6",
            title = "How we get there",
            question = "How does a neighbourhood actually change?",
            standfirst = "We can have healthier, cleaner communities today. The constraint " +
                "is not technology. It is demand.",
            sections = listOf(
                LevelSection(
                    heading = "You already vote every day",
                    body = "Every day we vote with our dollars to take part in a society " +
                        "of convenience and single-use packaged goods. That is not an " +
                        "accusation, it is leverage: the same mechanism runs in reverse. " +
                        "Unless local businesses know we want sustainable products and " +
                        "services, they cannot change their model -- and most of them " +
                        "will not gamble a thin margin on a preference nobody has stated " +
                        "out loud.",
                ),
                LevelSection(
                    heading = "Create the demand, out loud",
                    body = "Ask for it where it is heard: at the counter, in the review, " +
                        "at the neighbourhood association, in the email to the council " +
                        "member. Reduce the single-use packaging in your own life so the " +
                        "ask is credible. Then share your story with your community, " +
                        "because a request from one customer is noise and the same " +
                        "request from forty is a roadmap.",
                ),
                LevelSection(
                    heading = "Evidence is what makes the ask land",
                    body = "This is where the map earns its keep. 'There is litter around " +
                        "here' is an opinion. 'This corridor averages 31 pieces per 100 " +
                        "metres, 64% single-use plastic, with a 40% concentration within " +
                        "150 metres of three retail frontages' is a Litter Impact " +
                        "Assessment, and it is very hard to argue with. Scientists " +
                        "produce it; Advocates deliver it.",
                ),
            ),
            stats = listOf(
                KeyStat("Demand", "the actual bottleneck on local change", "Economics"),
                KeyStat("LIA", "the artefact that turns a complaint into a case", "TrashTalk"),
            ),
            quiz = listOf(
                QuizQuestion(
                    prompt = "Why do local businesses keep single-use packaging?",
                    options = listOf(
                        "They are indifferent to the environment",
                        "They have no evidence that customers want anything else",
                        "Alternatives are illegal",
                    ),
                    correctIndex = 1,
                    explanation = "Which makes stated, repeated, evidenced demand the " +
                        "highest-leverage thing a resident can produce.",
                ),
            ),
            action = "Pick one business on your block. Decide what you will ask them for.",
        ),

        // ------------------------------------------------------------------ 7
        NarrativeLevel(
            index = 7,
            eyebrow = "Level 7",
            title = "Join the movement",
            question = "Where do you fit?",
            standfirst = "You have the argument. Now pick the role you will actually play " +
                "in it -- and keep levelling, indefinitely.",
            sections = listOf(
                LevelSection(
                    heading = "Four paths, twenty badges, no ceiling",
                    body = "The first seven levels were the same for everyone, because " +
                        "the case had to be made before the game could mean anything. " +
                        "From here it diverges. Four paths -- Scientist, Eco-warrior, " +
                        "Advocate, Seeker -- each with five badges, each badge with five " +
                        "repeatable tiers. Bank five tiers and your path level goes up. " +
                        "There is no maximum.",
                ),
                LevelSection(
                    heading = "The paths are an engine, not a costume",
                    body = "Seekers and Eco-warriors engage each other by nature: both " +
                        "compete on output, so together they generate records, rivalry " +
                        "and volume. Scientists and Advocates work with each other by " +
                        "nature: one produces evidence, the other turns it into " +
                        "recruitment and pressure. The second pair supplies the first " +
                        "with motivation and resources; the first supplies the second " +
                        "with better data and more people. Pick where you push.",
                ),
                LevelSection(
                    heading = "What the ecosystem is actually for",
                    body = "Virtually eliminate street litter in Terrell Heights. " +
                        "Produce high-fidelity geospatial maps and Litter Impact " +
                        "Assessments that local governments, companies and " +
                        "neighbourhoods can act on. Give clean-up crews a way to recruit " +
                        "people who have never heard the phrase 'circular economy', and " +
                        "give researchers a dataset on how waste actually lands across a " +
                        "city. Then do it in the next neighbourhood.",
                ),
            ),
            stats = listOf(
                KeyStat("4", "paths to choose from", "TrashTalk"),
                KeyStat("20", "badges, five tiers each", "TrashTalk"),
                KeyStat("2025", "target: street litter virtually eliminated in Terrell Heights", "TrashTalk"),
            ),
            quiz = listOf(
                QuizQuestion(
                    prompt = "Which pair works together by nature (M2)?",
                    options = listOf(
                        "Seekers and Eco-warriors",
                        "Scientists and Advocates",
                        "Advocates and Eco-warriors",
                    ),
                    correctIndex = 1,
                    explanation = "Evidence plus persuasion. The other pair, M1, competes " +
                        "on output -- and uses what M2 generates.",
                ),
                QuizQuestion(
                    prompt = "How many banked tiers raise your path level?",
                    options = listOf("One", "Five", "Twenty"),
                    correctIndex = 1,
                    explanation = "Five tiers per level, repeatable, indefinitely.",
                ),
            ),
            action = "Choose your path. You can change it later, but choose deliberately.",
            pointsAward = 250,
        ),
    )

    fun level(index: Int): NarrativeLevel? = levels.firstOrNull { it.index == index }

    val count: Int get() = levels.size

    /**
     * The weekly trivia pool behind the Advocate's Education badge. Deliberately
     * broader than the manifesto so that repeat weeks keep teaching something.
     */
    val triviaPool: List<QuizQuestion> = listOf(
        QuizQuestion(
            prompt = "Roughly how much plastic and trash enters the ocean each year?",
            options = listOf("80,000 tonnes", "800,000 tonnes", "8 million tonnes"),
            correctIndex = 2,
            explanation = "About 8 million tonnes, the large majority of it from land.",
        ),
        QuizQuestion(
            prompt = "The Ellen MacArthur Foundation projected that by 2050 the ocean could contain:",
            options = listOf(
                "More plastic than fish, by weight",
                "Twice as many fish as today",
                "No plastic, under current trends",
            ),
            correctIndex = 0,
            explanation = "A projection, not a measurement -- and a projection that " +
                "assumes the current trend continues.",
        ),
        QuizQuestion(
            prompt = "What is a cigarette filter mostly made of?",
            options = listOf("Cotton", "Cellulose acetate, a plastic", "Paper"),
            correctIndex = 1,
            explanation = "Which is why butts are plastic litter carrying concentrated toxins.",
        ),
        QuizQuestion(
            prompt = "Why is multi-layer film packaging so hard to recycle?",
            options = listOf(
                "It is too light to collect",
                "Bonded layers of different materials cannot be economically separated",
                "It is banned from recycling by law",
            ),
            correctIndex = 1,
            explanation = "Mixed materials are the recycling system's hardest case.",
        ),
        QuizQuestion(
            prompt = "'Downcycling' means:",
            options = listOf(
                "Recycling at a lower temperature",
                "Each pass yields a lower-grade material",
                "Shipping waste to another country",
            ),
            correctIndex = 1,
            explanation = "Bottle to fibre to filler to waste.",
        ),
        QuizQuestion(
            prompt = "Which does the most to reduce a product's lifetime waste impact?",
            options = listOf(
                "Choices made at the design stage",
                "Consumer sorting at the bin",
                "Landfill engineering",
            ),
            correctIndex = 0,
            explanation = "Most of the impact is locked in before anyone buys the thing.",
        ),
        QuizQuestion(
            prompt = "A deposit return scheme mainly works by:",
            options = listOf(
                "Making littering illegal",
                "Giving the discarded item a cash value",
                "Reducing packaging weight",
            ),
            correctIndex = 1,
            explanation = "Price the material as an asset and it stops being litter.",
        ),
        QuizQuestion(
            prompt = "In a Litter Impact Assessment, why normalise counts per 100 m?",
            options = listOf(
                "To make the numbers look smaller",
                "So long and short segments can be compared fairly",
                "Because regulators require metric units",
            ),
            correctIndex = 1,
            explanation = "Density is comparable; raw counts are not.",
        ),
    )
}
