package app.parda.core

import app.parda.core.detect.Classifier
import app.parda.core.image.Box
import app.parda.core.image.ImageMaskPlan
import app.parda.core.image.ImageMasker
import app.parda.core.image.ImageRegion
import app.parda.core.image.OcrLine
import app.parda.core.image.OcrWord
import app.parda.core.policy.DataCategory
import app.parda.core.policy.DisclosureAction
import app.parda.core.policy.Policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Photos of a real Aadhaar, front and back, as ML Kit read them on the phone: same layout, same
 * kind of misreads, made-up values. The regional script (Telugu here) has no OCR model on the
 * device, so those lines arrive as stray glyphs; the name has no "Name:" label; the address runs
 * over five lines and its last line has no comma; the photo and the QR code are not text at all.
 */
class AadhaarImageTest {
    private fun line(y: Int, h: Int, vararg words: Pair<String, IntRange>) =
        OcrLine(words.map { (t, x) -> OcrWord(t, Box(x.first, y, x.last, y + h)) })

    private val front = listOf(
        line(990, 70, "ि" to 139..215),
        line(1010, 56, "भारत" to 526..624, "सरका" to 647..759),
        line(1075, 56, "Govermment" to 494..682, "of" to 703..731, "Ln" to 738..766),
        line(1160, 56, "A১K" to 570..670, "65" to 788..845),
        line(1225, 56, "KOTHA" to 597..760, "RAVI" to 781..900, "TEJA" to 920..1040),
        line(1290, 56, "৯" to 569..667, "/DOB:" to 678..855, "12UO8/1999" to 871..1095),
        line(1355, 56, "৪p&১/" to 570..751, "MALE" to 763..883),
        line(1420, 56, "Mobile" to 568..696, "No:" to 713..776, "9876543210" to 795..1040),
        line(1560, 70, "2345" to 568..709, "6789" to 733..875, "0124" to 919..1024),
        line(1700, 80, "मेरा" to 271..414, "आाधार," to 451..738, "मेरी" to 815..960, "पहचान" to 990..1269),
    )

    // ML Kit's block order, not reading order: the label comes before the header here.
    private val back = listOf(
        line(930, 50, "भारतीय" to 437..538, "विशिष्ट" to 554..654, "पहचान" to 671..767, "प्राधिकरण" to 784..916),
        line(1050, 50, "৩৫৯" to 253..376),
        line(1105, 50, "S/o" to 254..306, "K" to 320..358, "&३৩" to 397..443, "১১s" to 453..574, "০,£६-3«o৪/০০," to 583..852),
        line(1160, 50, "৩" to 246..316, "১6," to 343..398, "ক১১," to 522..666, "চম্্," to 674..773, "৪" to 784..837),
        line(1215, 50, "মি৪," to 245..345),
        line(1335, 50, "Address:" to 247..371),
        line(985, 50, "Unique" to 464..555, "ldentification" to 565..782, "AuthORty" to 805..949, "of" to 966..990, "thdia" to 1004..1084),
        line(1270, 50, "eoğ" to 245..309, "Jds-" to 317..390, "530017" to 400..505),
        line(1395, 50, "S/O" to 248..303, "KOTHA" to 310..452, "SURYA" to 460..607, "NARAYANA" to 615..862),
        line(1450, 50, "RAO," to 246..324, "12-7-45/3," to 332..495, "GANDHI" to 506..613, "NAGAR," to 624..748),
        line(1505, 50, "SECTOR" to 245..357, "4," to 366..390, "MVP" to 400..470, "COLONY," to 480..600, "Visakhapatnam" to 610..863),
        line(1560, 50, "(Urban)," to 236..359, "DIST:" to 370..450, "Visakhapatnam," to 462..677),
        line(1615, 50, "'Andhra" to 229..349, "Pradesh" to 376..477, "-530017" to 484..612),
        line(1000, 40, "AADHAAR" to 1168..1284),
        line(1690, 66, "2345" to 352..476, "6789" to 477..603, "0124" to 620..749),
        line(1800, 44, "1947" to 318..390),
        line(1800, 44, "help" to 596..661, "@uidai.gov.in" to 674..875),
        line(1800, 44, "wwW.uidai.gov.in" to 1020..1300),
    )

    private fun ImageMaskPlan.boxesOf(c: DataCategory) = fields.filter { it.category == c }.flatMap { it.boxes }
    private fun Box.overlapsRows(from: Int, to: Int) = top < to && bottom > from
    private fun List<Box>.coverX(y: Int, x: Int) = any { y in it.top..it.bottom && x in it.left..it.right }

    @Test fun `front - an unlabelled capitals name and the unreadable line above it are covered`() {
        val names = ImageMasker().plan(front, Policy()).boxesOf(DataCategory.PERSON_NAME)
        assertTrue(names.coverX(1250, 700) && names.coverX(1250, 1000), "English name: $names")
        assertTrue(names.coverX(1185, 600) && names.coverX(1185, 820), "regional-script name: $names")
    }

    @Test fun `front - a date of birth OCR misread is still covered, its label is not`() {
        val dob = ImageMasker().plan(front, Policy()).boxesOf(DataCategory.DATE_OF_BIRTH)
        assertTrue(dob.coverX(1315, 900) && dob.coverX(1315, 1090), "$dob")
        assertTrue(dob.none { it.left < 856 }, "the /DOB: label stays readable: $dob")
    }

    @Test fun `front - header, gender and footer stay readable`() {
        val plan = ImageMasker().plan(front, Policy())
        val all = plan.boxes(plan.categories.toSet())
        assertTrue(all.none { it.overlapsRows(1010, 1131) && it.right > 494 }, "header: $all")
        assertTrue(all.none { it.overlapsRows(1700, 1780) }, "footer: $all")
        assertTrue(all.none { it.overlapsRows(1360, 1405) && it.right > 763 }, "MALE: $all")
    }

    @Test fun `front - the number and mobile are covered`() {
        val plan = ImageMasker().plan(front, Policy())
        assertTrue(plan.boxesOf(DataCategory.GOV_ID).coverX(1590, 800))
        assertTrue(plan.boxesOf(DataCategory.PHONE).coverX(1445, 900))
    }

    @Test fun `front - with no face found, the photo left of the details is covered by layout`() {
        val photo = ImageMasker().plan(front, Policy()).boxesOf(DataCategory.FACE_PHOTO).single()
        assertTrue(photo.right < 569 && photo.right - photo.left > 250, "$photo")
        assertTrue(photo.top <= 1160 && photo.bottom >= 1600, "$photo")
    }

    @Test fun `front - a face the detector found is used instead of the layout guess`() {
        val face = ImageRegion(ImageRegion.Kind.FACE, Box(300, 1250, 460, 1450))
        val photo = ImageMasker().plan(front, Policy(), listOf(face)).boxesOf(DataCategory.FACE_PHOTO)
        assertEquals(1, photo.size, "$photo")
        val b = photo.single()
        assertTrue(b.left < 300 && b.right > 460 && b.top < 1250 && b.bottom > 1450, "the frame around the face: $b")
    }

    @Test fun `front - when OCR saw nothing of the regional name, the band above the English name is covered`() {
        val withoutRegional = front.filterNot { it.words.first().text == "A১K" }
        val names = ImageMasker().plan(withoutRegional, Policy()).boxesOf(DataCategory.PERSON_NAME)
        val band = names.filter { it.bottom <= 1225 }
        assertEquals(1, band.size, "$names")
        assertTrue(band.single().top >= 1131 && band.single().bottom - band.single().top >= 28, "clear of the header: $band")
    }

    @Test fun `back - every line of the English address is covered, the label is not`() {
        val address = ImageMasker().plan(back, Policy()).boxesOf(DataCategory.ADDRESS)
        for (y in listOf(1420, 1475, 1530, 1585, 1640)) assertTrue(address.coverX(y, 300), "line at $y: $address")
        assertTrue(address.coverX(1640, 600), "the PIN on a line with no comma")
        assertTrue(address.none { it.overlapsRows(1340, 1380) && it.left < 371 && it.right > 250 }, "Address: stays readable")
    }

    @Test fun `back - the unreadable regional address above the English one is covered`() {
        val address = ImageMasker().plan(back, Policy()).boxesOf(DataCategory.ADDRESS)
        for (y in listOf(1130, 1185, 1240, 1295)) assertTrue(address.coverX(y, 280), "line at $y: $address")
    }

    @Test fun `back - header and UIDAI contact lines stay readable`() {
        val plan = ImageMasker().plan(back, Policy())
        val all = plan.boxes(plan.categories.toSet())
        assertTrue(all.none { it.overlapsRows(930, 1035) && it.right > 437 && it.left < 1084 }, "header: $all")
        assertTrue(plan.fields.none { "uidai" in it.value }, "${plan.fields}")
    }

    @Test fun `back - with no QR decoded, the space right of the address is covered by layout`() {
        val qr = ImageMasker().plan(back, Policy()).boxesOf(DataCategory.QR_CODE).single()
        assertTrue(qr.left > 862 && qr.right >= 1300, "$qr")
        assertTrue(qr.top >= 1035 && qr.bottom < 1800 && qr.bottom > 1600, "$qr")
    }

    @Test fun `back - a decoded QR code is used instead of the layout guess`() {
        val code = ImageRegion(ImageRegion.Kind.CODE, Box(930, 1100, 1320, 1490))
        val qr = ImageMasker().plan(back, Policy(), listOf(code)).boxesOf(DataCategory.QR_CODE)
        assertEquals(1, qr.size)
        assertTrue(qr.single().left <= 930 && qr.single().right >= 1320)
    }

    @Test fun `face photos and QR codes follow the policy like any other field`() {
        val policy = Policy().with(DataCategory.FACE_PHOTO, DisclosureAction.ALLOW).with(DataCategory.QR_CODE, DisclosureAction.ALLOW)
        assertTrue(ImageMasker().plan(front, policy).boxesOf(DataCategory.FACE_PHOTO).isEmpty())
        assertTrue(ImageMasker().plan(back, policy).boxesOf(DataCategory.QR_CODE).isEmpty())
    }

    @Test fun `any image - a multi-line address under a label is covered down to its PIN`() {
        val form = listOf(
            line(0, 40, "Delivery" to 0..160, "details" to 180..320),
            line(60, 40, "Address:" to 0..160),
            line(110, 40, "Flat" to 0..80, "7B," to 100..160, "Lake" to 180..260, "View" to 280..360, "Apartments" to 380..580),
            line(160, 40, "Madhapur," to 0..180, "Hyderabad" to 200..380),
            line(210, 40, "Telangana" to 0..180, "500081" to 200..320),
            line(280, 40, "Deliver" to 0..140, "between" to 160..300, "6" to 320..340, "and" to 360..420, "9" to 440..460, "pm" to 480..520),
        )
        val plan = ImageMasker().plan(form, Policy())
        val address = plan.boxesOf(DataCategory.ADDRESS)
        for (y in listOf(130, 180, 230)) assertTrue(address.coverX(y, 50), "line at $y: $address")
        assertTrue(address.none { it.overlapsRows(0, 100) || it.overlapsRows(285, 320) }, "$address")
        assertTrue(plan.fields.none { it.category == DataCategory.FACE_PHOTO || it.category == DataCategory.QR_CODE }, "not an ID card")
    }

    @Test fun `any image - lines under an Address label with no PIN are left alone`() {
        val notes = listOf(
            line(0, 40, "Address:" to 0..160),
            line(50, 40, "to" to 0..40, "be" to 60..100, "confirmed" to 120..300),
        )
        assertTrue(ImageMasker().plan(notes, Policy()).fields.isEmpty())
    }

    @Test fun `text - a year of birth is a date of birth`() {
        val found = Classifier().classify("Year of Birth : 1990")
        assertEquals(listOf(DataCategory.DATE_OF_BIRTH to "1990"), found.map { it.category to it.value })
    }
}
