package com.mebit.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MovieRouteTest {
    @Test
    fun coversFortyThreeMoviePageBoards() {
        assertEquals(43, MovieRoute.entries.size)
        assertEquals(MovieRoute.Welcome, MovieRoute.start)
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
