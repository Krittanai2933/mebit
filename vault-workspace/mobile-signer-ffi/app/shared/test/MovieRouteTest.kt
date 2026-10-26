package com.mebit.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class MovieRouteTest {
    @Test
    fun coversFortyThreeMoviePageBoards() {
        assertEquals(43, MovieRoute.entries.size)
        assertEquals(MovieRoute.Welcome, MovieRoute.start)
    }
}
