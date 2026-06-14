package tachiyomi.core.common.util.system

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TallImageSplitCalculatorTest {

    @Test
    fun `calculatePartCount returns one part when image height equals optimal height`() {
        TallImageSplitCalculator.calculatePartCount(
            imageHeight = 30000,
            optimalImageHeight = 30000,
        ) shouldBe 1
    }

    @Test
    fun `shouldSplit returns false when tall image fits in one part`() {
        TallImageSplitCalculator.shouldSplit(
            imageWidth = 1000,
            imageHeight = 30000,
            optimalImageHeight = 30000,
        ) shouldBe false
    }

    @Test
    fun `shouldSplit returns true when tall image requires multiple parts`() {
        TallImageSplitCalculator.shouldSplit(
            imageWidth = 1000,
            imageHeight = 30001,
            optimalImageHeight = 30000,
        ) shouldBe true
    }
}
