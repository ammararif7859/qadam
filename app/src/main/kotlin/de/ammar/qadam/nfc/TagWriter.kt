package de.ammar.qadam.nfc

import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable

/**
 * Writes and reads Qadam's NFC stickers (SPEC §9: NTAG213/215). A tag holds two records
 * in NDEF (the standard NFC data format): the URI `qadam://tag/<name>`, and an "Android
 * application record" so the tap opens Qadam and no other app.
 */
object TagWriter {
    private const val PACKAGE = "de.ammar.qadam"
    private val NAME = Regex("^[a-z0-9_-]{1,24}$")

    fun isValidName(name: String) = NAME.matches(name)

    fun uriFor(name: String) = "qadam://tag/$name"

    fun nameFromUri(uri: Uri?): String? {
        if (uri == null || uri.scheme != "qadam" || uri.host != "tag") return null
        return uri.pathSegments.firstOrNull()?.takeIf { isValidName(it) }
    }

    private fun message(name: String) = NdefMessage(
        arrayOf(NdefRecord.createUri(uriFor(name)), NdefRecord.createApplicationRecord(PACKAGE)),
    )

    /** Writes the tag; returns null on success or a reason. Runs on the NFC thread. */
    fun write(tag: Tag, name: String): String? {
        if (!isValidName(name)) return "Name: 1–24 small letters, digits, - or _"
        val msg = message(name)
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                try {
                    if (!ndef.isWritable) return "This tag is read-only."
                    if (ndef.maxSize < msg.byteArrayLength) return "This tag is too small."
                    ndef.writeNdefMessage(msg)
                } finally {
                    ndef.close()
                }
                return null
            }
            val formatable = NdefFormatable.get(tag) ?: return "Not an NDEF tag (use NTAG213/215)."
            formatable.connect()
            try {
                formatable.format(msg)
            } finally {
                formatable.close()
            }
            return null
        } catch (e: Exception) {
            return "Writing failed: ${e.message ?: e.javaClass.simpleName}. Hold the tag still and try again."
        }
    }

    /** The Qadam name on a tag, or null. */
    fun read(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        val msg = ndef.cachedNdefMessage ?: return null
        return msg.records.firstNotNullOfOrNull { r -> nameFromUri(r.toUri()) }
    }
}
