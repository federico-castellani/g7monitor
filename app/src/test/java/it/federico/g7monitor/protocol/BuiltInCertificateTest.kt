package it.federico.g7monitor.protocol

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuiltInCertificateTest {
    /** The certificate shipped in res/raw (xDrip's public "G Key" QR) must import as a valid one. */
    @Test
    fun `shipped xDrip certificate is valid`() {
        val text = File("src/main/res/raw/xdrip_g_key.txt").readText()
        assertTrue(text.startsWith("xdp2:"))
        val certs = G7Certificates.fromXdripQr(text)
        assertNotNull(certs)
        assertTrue(certs!!.isValid)
    }
}
