package com.bess.salestrainer.core.data.mapper

import com.bess.salestrainer.core.model.ContentSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentSourceMapperTest {

    @Test
    fun `legacy bundled domain labels map to core content`() {
        assertEquals(ContentSource.CORE, "STORAGE".toContentSource())
        assertEquals(ContentSource.CORE, "WIND".toContentSource())
    }

    @Test
    fun `explicit external label remains external`() {
        assertEquals(ContentSource.EXTERNAL, "external".toContentSource())
    }
}
