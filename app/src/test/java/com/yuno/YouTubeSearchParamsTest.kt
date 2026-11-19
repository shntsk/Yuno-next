package com.yuno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tokens YouTube's own filter dialog shipped, as Flow pins them in `YouTubeSearchParamsTest`.
 * A failure means the protobuf layout changed, not the search screen.
 */
class YouTubeSearchParamsTest {
    @Test fun unfilteredRelevanceSendsNothing() {
        assertNull(YouTubeSearchParams.build())
    }

    @Test fun typeTokens() {
        assertEquals("EgIQAQ%3D%3D", YouTubeSearchParams.build(contentType = YouTubeSearchParams.ContentType.VIDEO))
        assertEquals("EgIQCQ%3D%3D", YouTubeSearchParams.build(contentType = YouTubeSearchParams.ContentType.SHORTS))
        assertEquals("EgIQAg%3D%3D", YouTubeSearchParams.build(contentType = YouTubeSearchParams.ContentType.CHANNEL))
        assertEquals("EgIQAw%3D%3D", YouTubeSearchParams.build(contentType = YouTubeSearchParams.ContentType.PLAYLIST))
        assertEquals("EgIQBA%3D%3D", YouTubeSearchParams.build(contentType = YouTubeSearchParams.ContentType.MOVIE))
    }

    @Test fun durationTokens() {
        assertEquals("EgIYBA%3D%3D", YouTubeSearchParams.build(duration = YouTubeSearchParams.Duration.UNDER_3_MINUTES))
        assertEquals("EgIYBQ%3D%3D", YouTubeSearchParams.build(duration = YouTubeSearchParams.Duration.THREE_TO_20_MINUTES))
        assertEquals("EgIYAg%3D%3D", YouTubeSearchParams.build(duration = YouTubeSearchParams.Duration.OVER_20_MINUTES))
    }

    @Test fun combinedSortAndFilters() {
        assertEquals(
            "CAMSBggFEAEYAg%3D%3D",
            YouTubeSearchParams.build(
                sortBy = YouTubeSearchParams.SortBy.VIEW_COUNT,
                contentType = YouTubeSearchParams.ContentType.VIDEO,
                duration = YouTubeSearchParams.Duration.OVER_20_MINUTES,
                uploadDate = YouTubeSearchParams.UploadDate.THIS_YEAR,
            ),
        )
    }
}
