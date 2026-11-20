package `in`.zeppay.zeppay

import `in`.zeppay.zeppay.data.Ifsc
import `in`.zeppay.zeppay.data.IfscDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IfscTest {
    private val dir = IfscDirectory { name -> javaClass.classLoader!!.getResourceAsStream(name) }
    private val empty = IfscDirectory { null }

    @Test fun formatIsCheckedNotLiveness() {
        assertTrue(Ifsc.isValidFormat("SBIN0001234"))
        assertTrue(Ifsc.isValidFormat(" sbin 0001234 "))
        assertTrue(Ifsc.isValidFormat("HDFC0CAACOB"))
        assertFalse(Ifsc.isValidFormat("SBIN1001234"))   // 5th must be 0
        assertFalse(Ifsc.isValidFormat("SBI00001234"))   // first 4 letters
        assertFalse(Ifsc.isValidFormat("SBIN000123"))    // 10 chars
        assertFalse(Ifsc.isValidFormat("SBIN0-01234"))
    }

    @Test fun problemsArePlainWords() {
        assertNull(Ifsc.problem("SBIN0001234"))
        assertEquals("The 5th character is always zero (0).", Ifsc.problem("SBIN1001234"))
        assertTrue(Ifsc.problem("SBIN000123")!!.contains("11 characters"))
        assertTrue(Ifsc.problem("SB1N0001234")!!.contains("first 4"))
        assertTrue(Ifsc.problem("SBIN0-01234")!!.contains("letters and numbers"))
        assertEquals("SBIN", Ifsc.bankCode("sbin0001234"))
    }

    @Test fun loadsMetaAndBanks() {
        val info = dir.info!!
        assertEquals("2026-08-15", info.rbiDate)
        assertEquals(2, dir.banks.size)
        assertTrue(dir.available)
        assertEquals("State Bank of India", dir.bank("sbin")!!.name)
    }

    @Test fun missingDatasetIsNotAvailableAndLooksUpNothing() {
        assertFalse(empty.available)
        assertNull(empty.info)
        assertNull(empty.lookup("SBIN0001234"))
        assertTrue(empty.searchBanks("state").isEmpty())
    }

    @Test fun searchesBanksByNameOrCode() {
        assertEquals("SBIN", dir.searchBanks("state bank").first().code)
        assertEquals("SBIN", dir.searchBanks("SBIN").first().code)
        assertEquals("HDFC", dir.searchBanks("hdfc").first().code)
        assertTrue(dir.searchBanks("nonexistent").isEmpty())
    }

    @Test fun searchesBranchesWithinABank() {
        val r = dir.searchBranches("SBIN", "surat")
        assertEquals(listOf("SBIN0001234"), r.map { it.ifsc })
        assertEquals(3, dir.branches("SBIN").size) // the malformed row is dropped
        assertEquals(1, dir.searchBranches("SBIN", "kolkata main").size)
        assertTrue(dir.searchBranches("ZZZZ", "x").isEmpty())
    }

    @Test fun lookupFindsListedCodesOnly() {
        assertEquals("KATARGAM", dir.lookup("sbin0001234")!!.branch)
        assertNotNull(dir.lookup("HDFC0CAACOB"))     // a co-op bank listed under its sponsor
        assertNull(dir.lookup("SBIN0009999"))        // well-formed but not listed: may still be valid
        assertNull(dir.lookup("SBIN1001234"))        // malformed
    }
}
