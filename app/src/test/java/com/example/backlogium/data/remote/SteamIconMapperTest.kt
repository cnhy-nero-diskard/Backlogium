package com.example.backlogium.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamIconMapperTest {

    @Test fun supportedSelectionsLeadBothChainsAndMissingSelectionFallsBackWithoutDuplicates() {
        val variants = com.example.backlogium.domain.GameArtworkVariant.entries
        for (variant in variants) for (portrait in listOf(false, true)) {
            val urls = SteamIconMapper.coverUrls(appId, variant, portrait)
            assertEquals(SteamIconMapper.artworkUrl(appId, variant), urls.first())
            assertEquals(5, urls.size)
            assertEquals(urls.size, urls.distinct().size)
            assertEquals(SteamIconMapper.coverUrls(appId, portrait = portrait).filterNot { it == urls.first() }, urls.drop(1))
        }
        assertEquals("$cdn/header.jpg", SteamIconMapper.coverUrls(appId).first())
        assertEquals("$cdn/hero_capsule.jpg", SteamIconMapper.coverUrls(appId, portrait = true).first())
    }

    private val appId = 268910L
    private val cdn = "https://cdn.cloudflare.steamstatic.com/steam/apps/$appId"

    @Test
    fun `list background fallbacks start with library hero`() {
        assertEquals(
            listOf(
                "$cdn/library_hero.jpg",
                "$cdn/capsule_616x353.jpg",
                "$cdn/hero_capsule.jpg",
                "$cdn/library_600x900.jpg",
            ),
            SteamIconMapper.listBackgroundFallbackUrls(appId),
        )
    }

    @Test
    fun `grid artwork fallbacks start with library hero after portrait asset`() {
        assertEquals(
            listOf(
                "$cdn/library_hero.jpg",
                "$cdn/library_600x900.jpg",
                "$cdn/header.jpg",
                "$cdn/capsule_616x353.jpg",
            ),
            SteamIconMapper.gridArtworkFallbackUrls(appId),
        )
    }
}
