package app.parda.core

import app.parda.core.image.Box
import app.parda.core.image.ImageMasker
import app.parda.core.image.OcrLine
import app.parda.core.image.OcrWord
import app.parda.core.policy.DataCategory
import app.parda.core.policy.DisclosureAction
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageMaskerTest {
    /** Lays words out left to right, 20 px per character, one line every 50 px. */
    private fun page(vararg lines: String): List<OcrLine> = lines.mapIndexed { li, line ->
        var x = 0
        OcrLine(
            line.split(' ').map { w ->
                val word = OcrWord(w, Box(x, li * 50, x + w.length * 20, li * 50 + 40))
                x += (w.length + 1) * 20
                word
            },
        )
    }

    private val card = page(
        "Name: Rajesh Kumar",
        "DOB: 14/08/1990",
        "2345 6789 0124",
        "PAN: ABCPK1234M",
        "12-4-89, Kondapur Main Road, Hyderabad 500084",
    )

    @Test fun `every sensitive field on an ID card gets covered`() {
        val plan = ImageMasker().plan(card, Policy())
        val found = plan.fields.map { it.category to it.value }
        assertTrue(DataCategory.GOV_ID to "2345 6789 0124" in found, "$found")
        assertTrue(DataCategory.GOV_ID to "ABCPK1234M" in found, "$found")
        val categories = plan.categories
        assertTrue(
            DataCategory.PERSON_NAME in categories && DataCategory.DATE_OF_BIRTH in categories &&
                DataCategory.ADDRESS in categories,
            "$found",
        )
    }

    @Test fun `labels stay readable, values are covered`() {
        val plan = ImageMasker().plan(card, Policy())
        val name = plan.fields.first { it.category == DataCategory.PERSON_NAME }
        // "Name:" is the first word (x 0..100); nothing may reach back over it.
        assertTrue(name.boxes.all { it.left > 100 }, "${name.boxes}")
        val tight = listOf(OcrLine(listOf(OcrWord("Name:", Box(0, 0, 100, 40)), OcrWord("Rajesh", Box(104, 0, 224, 40)))))
        assertTrue(ImageMasker().plan(tight, Policy()).fields.single().boxes.single().left > 100)
    }

    @Test fun `keep last 4 leaves the final group of an Aadhaar readable`() {
        val policy = Policy().with(DataCategory.GOV_ID, DisclosureAction.KEEP_LAST_4)
        val id = ImageMasker().plan(card, policy).fields.first { it.value == "2345 6789 0124" }
        assertTrue(id.keepsLast4)
        // "0124" occupies x 200..280 on line 3; nothing may cover it.
        assertTrue(id.boxes.none { it.top >= 90 && it.right > 205 }, "${id.boxes}")
        assertEquals(2, id.boxes.size)
    }

    /** Seen on a phone: OCR merged "Address:12-4-89," into one word and the first digit showed. */
    @Test fun `a value that starts inside a word is covered from a character early`() {
        val address = ImageMasker().plan(page("Address:12-4-89, Kondapur Main Road, Hyderabad 500084"), Policy())
            .fields.first { it.category == DataCategory.ADDRESS }
        // "Address:" is 8 of the word's 16 characters (x 0..160); "1" begins at x 160.
        assertTrue(address.boxes.first().left <= 150, "${address.boxes}")
    }

    /** Seen on a phone: ML Kit's box for "12-4-89," started to the right of the "1". */
    @Test fun `bars reach past the edge of an OCR word box`() {
        val address = ImageMasker().plan(page("Address: 12-4-89, Kondapur Main Road, Hyderabad 500084"), Policy())
            .fields.first { it.category == DataCategory.ADDRESS }
        // "12-4-89," starts at x 180 and the label ends at x 160: the bar begins between them.
        val first = address.boxes.first()
        assertTrue(first.left in 161..175, "$first")
    }

    @Test fun `an OCR misread that breaks the checksum is still covered`() {
        val plan = ImageMasker().plan(page("2345 6789 0125"), Policy())
        assertEquals(DataCategory.GOV_ID, plan.fields.single().category)
    }

    @Test fun `allowed categories are left alone and unticked ones are not painted`() {
        val policy = Policy().with(DataCategory.PERSON_NAME, DisclosureAction.ALLOW)
        val plan = ImageMasker().plan(card, policy)
        assertFalse(plan.fields.any { it.category == DataCategory.PERSON_NAME })
        val withoutDob = plan.boxes(plan.categories.toSet() - DataCategory.DATE_OF_BIRTH)
        assertTrue(withoutDob.size < plan.boxes(plan.categories.toSet()).size)
    }
}
