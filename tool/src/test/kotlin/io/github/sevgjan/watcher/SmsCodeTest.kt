package io.github.sevgjan.watcher

import io.github.sevgjan.watcher.engine.SmsCode
import io.github.sevgjan.watcher.engine.parseSmsGranted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmsCodeTest {
    @Test
    fun onlyChosenSendersAreAccepted() {
        val senders = listOf("My Bank", "Example")
        assertEquals("My Bank", SmsCode.match("My Bank", senders))
        assertEquals("My Bank", SmsCode.match("mybank", senders))
        assertEquals("Example", SmsCode.match("EXAMPLE", senders))
        assertNull(SmsCode.match("My Bank Fake", senders))
        assertNull(SmsCode.match("Mom", senders))
        assertNull(SmsCode.match("+15555550123", senders))
        assertNull(SmsCode.match(null, senders))
        assertNull(SmsCode.match("My Bank", emptyList()))
        assertNull(SmsCode.match("", listOf(" ")))
    }

    @Test
    fun findsTheCode() {
        assertEquals("482913", SmsCode.codeIn("Your the bank code is 482913. Do not share it."))
        assertEquals("1234", SmsCode.codeIn("Kodi: 1234"))
        assertNull(SmsCode.codeIn("Welcome to the bank"))
        assertNull(SmsCode.codeIn("Order 123"))
        assertNull(SmsCode.codeIn("Account 1234567890123"))
        // Letters and digits mixed in one code.
        assertEquals("4aB9cD", SmsCode.codeIn("Your login code: 4aB9cD valid 3 minutes."))
        assertEquals("K7M2QX", SmsCode.codeIn("Code K7M2QX for the bank"))
        // The word after a colon beats an earlier amount.
        assertEquals("7Q2R9T", SmsCode.codeIn("Payment 1500 EUR. Code: 7Q2R9T"))
        assertNull(SmsCode.codeIn("Thank you for banking with us"))
    }

    @Test
    fun readsGrantedState() {
        assertTrue(parseSmsGranted("      android.permission.RECEIVE_SMS: granted=true, flags=[ USER_SET]"))
        assertFalse(parseSmsGranted("      android.permission.RECEIVE_SMS: granted=false, flags=[ ]"))
        assertFalse(parseSmsGranted(""))
    }
}
