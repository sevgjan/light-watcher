package io.github.sevgjan.watcher

import io.github.sevgjan.watcher.engine.PairingRules
import io.github.sevgjan.watcher.engine.parseGranted
import io.github.sevgjan.watcher.engine.parseSmsGranted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {
    @Test
    fun onlyKeysYouTypeCanBeAnswered() {
        assertTrue(PairingRules.canAnswer(PairingRules.PIN))
        assertTrue(PairingRules.canAnswer(PairingRules.PIN_16_DIGITS))
        // Comparing numbers, tapping Allow and the rest need a system-only permission.
        assertFalse(PairingRules.canAnswer(PairingRules.PASSKEY_CONFIRMATION))
        assertFalse(PairingRules.canAnswer(PairingRules.CONSENT))
        assertFalse(PairingRules.canAnswer(PairingRules.DISPLAY_PASSKEY))
        assertFalse(PairingRules.canAnswer(PairingRules.DISPLAY_PIN))
        assertFalse(PairingRules.canAnswer(PairingRules.PASSKEY))
        assertFalse(PairingRules.canAnswer(-1))
    }

    @Test
    fun cleansTheTypedKey() {
        assertEquals("5380", PairingRules.cleanPin("5380"))
        assertEquals("5380", PairingRules.cleanPin("  5380 "))
        assertEquals("0000", PairingRules.cleanPin("0000"))
        assertNull(PairingRules.cleanPin(""))
        assertNull(PairingRules.cleanPin("   "))
        assertEquals("1234567890123456", PairingRules.cleanPin("1234567890123456"))
        assertNull(PairingRules.cleanPin("12345678901234567"))
    }

    @Test
    fun explainsWhyNot() {
        assertTrue("same number" in PairingRules.whyNot(PairingRules.PASSKEY_CONFIRMATION))
        assertTrue("Allow" in PairingRules.whyNot(PairingRules.CONSENT))
        assertTrue(PairingRules.whyNot(99).isNotBlank())
    }

    @Test
    fun readsGrantedPermissions() {
        val out = """
            android.permission.BLUETOOTH_CONNECT: granted=true
            android.permission.RECEIVE_SMS: granted=false
        """.trimIndent()
        assertTrue(parseGranted(out, "BLUETOOTH_CONNECT"))
        assertFalse(parseGranted(out, "RECEIVE_SMS"))
        assertFalse(parseGranted(out, "POST_NOTIFICATIONS"))
        assertFalse(parseSmsGranted(out))
        assertTrue(parseSmsGranted("android.permission.RECEIVE_SMS: granted=true"))
    }
}
