package app.parda.core.disclosure

import app.parda.core.policy.DataCategory

/**
 * Picks realistic made-up values for one sanitized text. Deterministic: values come from fixed
 * lists in order, never from a model, and the same text always gets the same stand-ins. A
 * stand-in never shares a word with the original text, so swapping it back cannot touch a real
 * value, and two different real values never share a stand-in.
 */
class StandIns(original: String) {
    private val taken: MutableSet<String> = WORD.findAll(original).map { it.value.lowercase() }.toMutableSet()
    private var nextName = 0
    private var nextEmail = 0
    private var nextStreet = 0

    /**
     * A stand-in for [value], or null when there is none to give (a name in Devanagari or Telugu
     * script); the caller then falls back to a placeholder. [before] is the text just ahead of
     * the value, where an honorific ("Mrs.") says which list a name comes from.
     */
    fun next(category: DataCategory, value: String, before: String): String? = when (category) {
        DataCategory.PERSON_NAME -> name(value, before)
        DataCategory.EMAIL -> email()
        DataCategory.ADDRESS -> address(value)
        else -> null
    }

    private fun name(value: String, before: String): String? {
        if (value.any { it.isLetter() && it.code > 0x24F }) return null
        val list = when {
            FEMALE.containsMatchIn(before) -> FEMALE_NAMES
            MALE.containsMatchIn(before) -> MALE_NAMES
            else -> ANY_NAMES
        }
        val words = value.split(' ', '\t').count { it.any(Char::isLetter) }
        val pick = take(list, nextName) { i -> nextName = i } ?: return null
        // "Mrs. Lakshmi" gets one word back, "Rajesh Kumar Sharma" a first and last name.
        return if (words <= 1) pick.substringBefore(' ') else pick
    }

    private fun email(): String? {
        val pick = take(ANY_NAMES, nextEmail) { i -> nextEmail = i } ?: return null
        return pick.lowercase().replace(' ', '.') + "@example.com" // example.com is reserved for exactly this (RFC 2606)
    }

    private fun address(value: String): String? {
        val street = take(STREETS, nextStreet) { i -> nextStreet = i } ?: return null
        // The city keeps answers about rent or commute useful; the house, street and PIN code go.
        val city = CITIES.firstOrNull { Regex("""\b$it\b""", RegexOption.IGNORE_CASE).containsMatchIn(value) }
        return if (city != null) "$street, $city" else street
    }

    /** The first entry from [start] whose words are all unused, which it then marks as used. */
    private fun take(list: List<String>, start: Int, advance: (Int) -> Unit): String? {
        for (i in start until list.size) {
            val words = WORD.findAll(list[i]).map { it.value.lowercase() }.toList()
            if (words.any { it in taken && it !in STREET_WORDS }) continue
            taken += words
            advance(i + 1)
            return list[i]
        }
        return null
    }

    companion object {
        private val WORD = Regex("""\p{L}+""")
        private val FEMALE = Regex("""\b(Mrs|Ms|Smt|Kumari|Miss)\.?\s*$""")
        private val MALE = Regex("""\b(Mr|Shri|Sri)\.?\s*$""")

        val MALE_NAMES = listOf(
            "Arjun Mehta", "Rohan Iyer", "Vikram Bhatia", "Karthik Menon", "Aditya Joshi", "Siddharth Bose",
            "Nikhil Kapoor", "Varun Desai", "Manish Tandon", "Harsh Vardhan", "Pranav Kulkarni", "Dev Malhotra",
        )
        val FEMALE_NAMES = listOf(
            "Ananya Rao", "Priya Nair", "Kavya Saxena", "Meera Pillai", "Sneha Chawla", "Divya Arora",
            "Isha Banerjee", "Nandini Ghosh", "Tanvi Shetty", "Riya Sinha", "Aditi Bajaj", "Pooja Dutta",
        )
        /** Alternating, when nothing says which. */
        val ANY_NAMES = MALE_NAMES.zip(FEMALE_NAMES).flatMap { listOf(it.first, it.second) }

        private val STREETS = listOf(
            "Plot 17, Lakeview Colony", "Flat 3B, Palm Grove Apartments", "House 42, Rosewood Enclave",
            "Door 9, Sunrise Residency", "Flat 204, Silver Oak Towers", "House 8, Maple Street",
        )
        /** Words that may repeat across stand-in streets ("Flat", "House") without harm. */
        private val STREET_WORDS = setOf("plot", "flat", "house", "door", "b")

        private val CITIES = listOf(
            "Hyderabad", "Secunderabad", "Bengaluru", "Bangalore", "Chennai", "Mumbai", "New Delhi", "Delhi", "Pune",
            "Kolkata", "Ahmedabad", "Jaipur", "Lucknow", "Kochi", "Visakhapatnam", "Vijayawada", "Warangal", "Noida",
            "Gurugram", "Gurgaon", "Chandigarh", "Indore", "Bhopal", "Nagpur", "Coimbatore", "Mysuru", "Thane",
        )
    }
}
