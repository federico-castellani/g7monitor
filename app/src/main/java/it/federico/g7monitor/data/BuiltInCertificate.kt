package it.federico.g7monitor.data

import android.content.Context
import it.federico.g7monitor.R
import it.federico.g7monitor.protocol.G7Certificates

/**
 * xDrip's public "G Key" certificate (the QR text of G7_keks_QR.png in the repository), used for
 * the first pairing when the user has not imported another one: nothing to set up.
 */
object BuiltInCertificate {
    @Volatile
    private var cached: G7Certificates? = null

    fun get(context: Context): G7Certificates? = cached
        ?: context.resources.openRawResource(R.raw.xdrip_g_key).bufferedReader()
            .use { G7Certificates.fromXdripQr(it.readText()) }
            .also { cached = it }
}
