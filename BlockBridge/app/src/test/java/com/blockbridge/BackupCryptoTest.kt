package com.blockbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupCryptoTest {
    @Test fun roundTripAndWrongPassword() {
        val secret = "[Interface]\nPrivateKey = c2VjcmV0\n"
        val enc = BackupCrypto.encrypt(secret, "correct horse")
        assertFalse(enc.toString().contains("c2VjcmV0"))
        assertEquals(secret, BackupCrypto.decrypt(enc, "correct horse"))
        val wrong = runCatching { BackupCrypto.decrypt(enc, "wrong") }
        assertEquals(javax.crypto.AEADBadTagException::class.java, wrong.exceptionOrNull()?.javaClass)
    }
}
