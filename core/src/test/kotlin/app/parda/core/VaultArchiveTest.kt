package app.parda.core

import app.parda.core.disclosure.Sanitizer
import app.parda.core.disclosure.VaultArchive
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultArchiveTest {
    private val letter = Sanitizer().sanitize(Samples.SALARY_LETTER, Policy())
    private val lease = Sanitizer().sanitize("Tenant: Ms Kavya Menon, rent Rs 24,000, call +91 90000 11111.", Policy())
    private val hour = 60 * 60 * 1000L

    @Test fun `a reply pasted back later reads with the real names`() {
        val archive = VaultArchive().add("Salary letter", letter.vault, now = 0)
        val reply = "Dear PERSON_1, at <AMOUNT_1> you are paid fairly for your role."
        val hb = archive.bestFor(reply)!!
        assertEquals("Salary letter", hb.title)
        assertTrue("Rajesh Kumar" in hb.vault.rehydrate(reply) && "₹18,40,000" in hb.vault.rehydrate(reply))
    }

    @Test fun `the reply is matched to the hand-back it answers`() {
        val archive = VaultArchive().add("Salary letter", letter.vault, now = 0).add("Lease", lease.vault, now = 1)
        // Both vaults have PERSON_1; only the letter has EMAIL_1 and ADDRESS_1.
        assertEquals("Salary letter", archive.bestFor("<PERSON_1> should update <EMAIL_1> and <ADDRESS_1>.")!!.title)
        // A tie goes to the newest.
        assertEquals("Lease", archive.bestFor("Ask <PERSON_1> first.")!!.title)
        assertNull(archive.bestFor("No tokens here."))
    }

    @Test fun `vaults expire after a day, and only restorable values are kept`() {
        val archive = VaultArchive().add("Salary letter", letter.vault, now = 0)
        assertTrue(archive.handBacks.single().entries.all { it.restorable }, "blocked values never left, so none are kept")
        assertFalse(letter.vault.entries.all { it.restorable }, "the letter did have blocked values")
        assertEquals(1, archive.expire(23 * hour).handBacks.size)
        assertEquals(0, archive.expire(25 * hour).handBacks.size)
    }

    @Test fun `forgetting one, the cap, and a round trip through JSON`() {
        var archive = VaultArchive()
        repeat(VaultArchive.MAX + 5) { archive = archive.add("Copy $it", letter.vault, now = it.toLong()) }
        assertEquals(VaultArchive.MAX, archive.handBacks.size)
        assertEquals("Copy ${VaultArchive.MAX + 4}", archive.handBacks.first().title)
        val one = archive.handBacks.first().id
        assertEquals(VaultArchive.MAX - 1, archive.without(one).handBacks.size)
        assertEquals(archive, VaultArchive.fromJson(archive.toJson()))
        assertEquals(VaultArchive(), VaultArchive.fromJson("not json"))
    }

    @Test fun `a reply is recognised by its tokens`() {
        assertTrue(VaultArchive.looksLikeReply("Hello <PERSON_1>"))
        assertFalse(VaultArchive.looksLikeReply("Hello Rajesh"))
    }
}
