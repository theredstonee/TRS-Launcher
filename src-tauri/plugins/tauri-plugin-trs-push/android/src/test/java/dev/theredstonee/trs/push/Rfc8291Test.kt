package dev.theredstonee.trs.push

import com.google.crypto.tink.apps.fixed_webpush.WebPushHybridDecrypt
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Entschlüsselung wie in der App: der Connector entschlüsselt mit Tinks `fixed_webpush` (RFC 8291, aes128gcm).
 * Testvektor aus RFC 8291, Anhang A – derselbe, gegen den der Server seine Verschlüsselung prüft.
 */
class Rfc8291Test {
    private fun b64(s: String): ByteArray = Base64.getUrlDecoder().decode(s.replace(" ", ""))

    private val uaPublic = b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
    private val uaPrivate = b64("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")
    private val auth = b64("BTBZMqHH6r4Tts7J_aSIgg")
    private val header = b64("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")
    private val ciphertext = b64("8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ")
    private val plaintext = b64("V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24")

    private fun decrypter() = WebPushHybridDecrypt.Builder()
        .withAuthSecret(auth)
        .withRecipientPublicKey(uaPublic)
        .withRecipientPrivateKey(uaPrivate)
        .build()

    @Test
    fun decryptsTheAppendixAVector() {
        assertEquals(86, header.size)
        val message = header + ciphertext
        val clear = decrypter().decrypt(message, null)
        assertArrayEquals(plaintext, clear)
        assertEquals("When I grow up, I want to be a watermelon", String(clear, Charsets.UTF_8))
    }

    @Test
    fun rejectsTamperedMessages() {
        val message = header + ciphertext
        message[message.size - 1] = (message[message.size - 1].toInt() xor 1).toByte()
        try {
            decrypter().decrypt(message, null)
            fail("verändertes Chiffrat darf nicht entschlüsselt werden")
        } catch (e: java.security.GeneralSecurityException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun rejectsTheWrongAuthSecret() {
        val wrong = WebPushHybridDecrypt.Builder()
            .withAuthSecret(ByteArray(16))
            .withRecipientPublicKey(uaPublic)
            .withRecipientPrivateKey(uaPrivate)
            .build()
        try {
            wrong.decrypt(header + ciphertext, null)
            fail("falsches Auth-Geheimnis darf nicht entschlüsseln")
        } catch (e: java.security.GeneralSecurityException) {
            assertNotNull(e)
        }
    }
}
