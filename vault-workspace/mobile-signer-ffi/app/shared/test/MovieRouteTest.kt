package com.mebit.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MovieRouteTest {
    @Test
    fun coversFortyFourMoviePageBoards() {
        assertEquals(44, MovieRoute.entries.size)
        assertEquals(MovieRoute.Welcome, MovieRoute.start)
        assertEquals("onboarding/import-seed", MovieRoute.ImportSeed.path)
        assertEquals("IMPORT SEED", MovieRoute.ImportSeed.eyebrow)
    }

    @Test
    fun everyRouteHasUniquePath() {
        val paths = MovieRoute.entries.map { it.path }
        assertEquals(paths.size, paths.toSet().size)
    }

    @Test
    fun eyebrowsAreUppercaseEnglish() {
        MovieRoute.entries.forEach { route ->
            assertTrue(
                route.eyebrow == route.eyebrow.uppercase(),
                "eyebrow must be uppercase: ${route.name} -> ${route.eyebrow}",
            )
        }
    }
}
