package it.federico.g7monitor.protocol

import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Optional "app certificate" material that some G7 sensors require the first time
 * they are bonded to a new phone. xDrip calls these keks_p1, keks_p2, keks_p3
 * (certificate 1, certificate 2, private key) and loads them from a QR code.
 * They are *not* shipped with this app.
 */
class G7Certificates(val partA: ByteArray, val partB: ByteArray, val partC: ByteArray) {
    val isValid get() = partA.size > 100 && partB.size > 100 && partC.size > 100

    /** Standard hex, the same format xDrip keeps in its keks_p1..3 preferences. */
    fun toHex(): Triple<String, String, String> = Triple(partA.toHex(), partB.toHex(), partC.toHex())

    companion object {
        /** Parse the three values as stored in xDrip preferences (plain hex, non-hex chars ignored). */
        fun fromHex(p1: String, p2: String, p3: String): G7Certificates? {
            val a = tolerantHex(p1) ?: return null
            val b = tolerantHex(p2) ?: return null
            val c = tolerantHex(p3) ?: return null
            return G7Certificates(a, b, c).takeIf { it.isValid }
        }

        private fun tolerantHex(s: String): ByteArray? {
            val clean = s.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }
            if (clean.isEmpty() || clean.length % 2 != 0) return null
            return clean.hexToBytes()
        }

        /**
         * Parse xDrip's "G Key" QR code text: "xdp2:" + base64(gzip(binary prefs map)),
         * where the map holds b__keks_p1, b__keks_p2, b__keks_p3 (see xDrip QRcodeUtils).
         */
        fun fromXdripQr(text: String): G7Certificates? = try {
            val body = text.trim().removePrefix("xdp2:")
            val gz = java.util.Base64.getDecoder().decode(body.filter { !it.isWhitespace() })
            val raw = java.util.zip.GZIPInputStream(gz.inputStream()).use { it.readBytes() }
            val bb = java.nio.ByteBuffer.wrap(raw)
            val count = bb.short.toInt()
            val map = HashMap<String, ByteArray>()
            repeat(count) {
                val k = ByteArray(bb.short.toInt()).also { bb.get(it) }
                val v = ByteArray(bb.short.toInt() and 0xFFFF).also { bb.get(it) }
                map[String(k, Charsets.UTF_8).removePrefix("b__")] = v
            }
            val a = map["keks_p1"]; val b = map["keks_p2"]; val c = map["keks_p3"]
            if (a == null || b == null || c == null) null else G7Certificates(a, b, c).takeIf { it.isValid }
        } catch (e: Exception) {
            null
        }
    }
}

/** What the BLE layer must do next. */
sealed interface AuthAction {
    /**
     * Write [extraData] (if any) to the ExtraData characteristic in 20 byte chunks
     * (write without response), then [command] (if any) to Authentication (with response).
     */
    class Write(val command: ByteArray?, val extraData: ByteArray?) : AuthAction {
        override fun toString() = "Write(cmd=${command?.toHex()}, data=${extraData?.size ?: 0}B)"
    }

    /** Authenticated and bonded: request glucose on the Control characteristic. */
    data object ReadyForData : AuthAction

    /** Sensor rejected us and is not bonded: remove the Android bond and retry later. */
    data object BondFailure : AuthAction

    /** Nothing to do / unknown state: caller should reset and reconnect later. */
    data object Stalled : AuthAction

    /** The sensor wants the Android BLE bond to be created now. */
    data object CreateBond : AuthAction

    /** Wrong code / mismatching sensor: forget saved key and rescan. */
    class Mismatch(val reason: String) : AuthAction

    /** Configuration problem (e.g. certificates required but missing). */
    class ConfigError(val reason: String) : AuthAction
}

/**
 * Port of xDrip's keks Plugin state machine (jamorham.keks.Plugin), which authenticates
 * with a Dexcom G7 using its 4 digit pairing code.
 *
 * Usage, per BLE connection:
 *  1. enable notifications on ExtraData, indications on Authentication
 *  2. [onConnected]  -> execute returned action
 *  3. every Authentication indication -> [onAuthenticationData]
 *  4. every ExtraData notification -> [onExtraData]
 *
 * One instance lives as long as the sensor (pairing code) so J-PAKE results can be reused.
 */
class G7Auth(
    val pairingCode: String,
    savedKey: ByteArray? = null,
    private val certificates: G7Certificates? = null,
    private val log: (String) -> Unit = {},
) {
    enum class State {
        Init, Unknown, RoundStart, Round1, Round2, Round3, Pairing, BondFailure, RequestAuth,
        ChallengeReply, SendCertificate0, SendCertificate1, SendCertificate2,
        SendCertificate1out, SendCertificate2out, SendKeyChallenge, SendKeyChallengeOut,
        GetData, GetData2
    }

    private var jpake = JPake.Session(pairingCode)
    private val longPassword = jpake.passwordBytes.size > 4

    /** 16 byte key derived from J-PAKE; persist it per sensor MAC to skip J-PAKE next time. */
    var savedKey: ByteArray? = savedKey?.takeIf { it.size == 16 }
        private set

    var state = State.Init
        private set

    /** Informational only: whether Android already has a bond with the sensor. */
    var locallyBonded: Boolean = false

    private var sequence = 0
    private var accumulator = ByteArray(0)
    private var expectedSize = 0
    private var signedResponse: ByteArray? = null
    private var lastToken: ByteArray = ByteArray(8)
    private var challenge: ByteArray = ByteArray(8)

    private fun changeState(new: State) {
        log("auth: $state -> $new")
        if (new !in KEEP_ACCUMULATOR) accumulator = ByteArray(0)
        state = new
    }

    private fun reset() {
        savedKey = null
        jpake.reset()
    }

    /** The key to persist after a successful authentication. */
    fun sharedKey(): ByteArray? {
        if (savedKey == null) savedKey = jpake.sharedKey()
        return savedKey
    }

    fun forgetKey() {
        reset()
        jpake = JPake.Session(pairingCode)
    }

    fun onConnected(): AuthAction {
        sequence = 0
        if (savedKey == null && jpake.peerPackets[3] == null) reset()
        changeState(State.RoundStart)
        return next()
    }

    fun isBondRequest(data: ByteArray): Boolean =
        BOND_TRIGGERS.any { it.contentEquals(data) }

    /** Indication from the Authentication characteristic. May return 0..2 actions. */
    fun onAuthenticationData(data: ByteArray): List<AuthAction> {
        val out = mutableListOf<AuthAction>()
        if (isBondRequest(data)) out += AuthAction.CreateBond
        try {
            if (receivedResponse(data)) out += next()
        } catch (e: MismatchException) {
            out += AuthAction.Mismatch(e.message ?: "mismatch")
        } catch (e: ConfigException) {
            out += AuthAction.ConfigError(e.message ?: "config")
        }
        return out
    }

    /** Notification from the ExtraData characteristic. */
    fun onExtraData(data: ByteArray): AuthAction? = if (receivedData(data)) next() else null

    private class MismatchException(msg: String) : Exception(msg)
    private class ConfigException(msg: String) : Exception(msg)

    private fun receivedResponse(data: ByteArray): Boolean {
        log("auth rx ${data.toHex()} in $state")
        when (state) {
            State.RequestAuth -> {
                if (!verifyChallenge(data)) {
                    reset()
                    if (sequence > 1) throw MismatchException("Challenge mismatch - wrong pairing code?")
                    return false
                }
                challenge = data.copyOfRange(9, 17)
                return true
            }

            State.ChallengeReply -> {
                val authenticated = data.size >= 3 && data[0].toInt() == 0x05 && data[1].toInt() == 1
                val bondedByte = if (data.size >= 3 && data[0].toInt() == 0x05) data[2].toInt() else 0
                val bonded = bondedByte == 1
                if (bondedByte == 3) reset()
                if (!authenticated) {
                    log("auth: could not authenticate")
                    reset()
                    changeState(if (!bonded) State.BondFailure else State.Unknown)
                    return true
                }
                if (bonded) {
                    changeState(if (longPassword) State.GetData else State.GetData2)
                    return true
                }
                expectedSize = 0
                signedResponse = null
                if (longPassword) {
                    changeState(State.Pairing)
                } else if (certificates?.isValid == true) {
                    changeState(State.SendCertificate0)
                } else {
                    throw ConfigException("Il sensore richiede i certificati (keks) per il primo abbinamento")
                }
                return true
            }

            State.Pairing, State.SendKeyChallengeOut -> return true

            State.SendCertificate1, State.SendCertificate2 -> {
                val size = certInfoSize(data) ?: throw ConfigException("Certificato rifiutato dal sensore")
                expectedSize = size
                changeState(state) // keeps accumulator
                return true
            }

            State.SendKeyChallenge -> {
                if (data.size > 2 && data[1].toInt() != 0) throw ConfigException("Chiave certificato rifiutata")
                val certs = certificates ?: throw ConfigException("Certificati mancanti")
                signedResponse = signChallenge(certs.partC, data.copyOfRange(2, 18))
                return true
            }

            else -> return false
        }
    }

    private fun receivedData(data: ByteArray): Boolean {
        accumulator += data
        val expected = expectedBytes()
        log("auth data +${data.size} = ${accumulator.size}/$expected in $state")
        if (accumulator.size < expected) return false
        val pos = when (state) {
            State.Round1 -> 1; State.Round2 -> 2; State.Round3 -> 3; else -> 0
        }
        val bytes = accumulator
        accumulator = ByteArray(0)
        return when (state) {
            State.Round1, State.Round2, State.Round3 -> {
                jpake.peerPackets[pos] = JPake.Packet.parse(bytes)
                val ok = when (state) {
                    State.Round1 -> jpake.validatePeerRound1()
                    State.Round2 -> jpake.validatePeerRound2()
                    else -> jpake.validatePeerRound3()
                }
                if (!ok) {
                    log("auth: validation failed in $state")
                    jpake.peerPackets[pos] = null
                }
                ok
            }
            // The sensor's own certificate: accepted as received (same as xDrip).
            State.SendCertificate1out, State.SendCertificate2out -> true
            else -> false
        }
    }

    private fun expectedBytes(): Int = when (state) {
        State.Round1, State.Round2, State.Round3 -> JPake.PACKET_SIZE
        State.SendCertificate1, State.SendCertificate1out,
        State.SendCertificate2, State.SendCertificate2out -> if (expectedSize > 0) expectedSize else Int.MAX_VALUE
        State.SendKeyChallenge -> 64
        else -> Int.MAX_VALUE
    }

    private fun authRequest(): ByteArray {
        lastToken = randomBytes(8)
        // Always slot 2, exactly like xDrip on phones. (xDrip only switches slot on its
        // "BlueJay" watch; using another slot talks to a different paired device's key.)
        return byteArrayOf(0x02) + lastToken + byteArrayOf(SLOT)
    }

    private fun sequencePacket(packet: ByteArray?): AuthAction.Write {
        sequence++
        val param = when (state) {
            State.Round1 -> 0; State.Round2 -> 1; State.Round3 -> 2; else -> -1
        }
        val cmd = if (param < 0) null else byteArrayOf(0x0A, param.toByte())
        return AuthAction.Write(cmd, packet)
    }

    private fun currentKey(): ByteArray? = savedKey ?: jpake.sharedKey()

    private fun verifyChallenge(data: ByteArray): Boolean {
        if (savedKey != null) return true
        if (data.size < 17) return false
        val h = JPake.challengeHash(currentKey() ?: return false, lastToken) ?: return false
        for (i in 0 until 8) if (h[i] != data[i + 1]) return false
        return true
    }

    /** Port of Plugin.aNext(). */
    private fun next(): AuthAction {
        log("auth next in $state")
        return when (state) {
            State.RoundStart -> if (jpake.peerPackets[3] != null || savedKey != null) {
                state = State.RequestAuth
                AuthAction.Write(authRequest(), null)
            } else {
                state = State.Round1
                sequencePacket(null)
            }

            State.Round1 -> { state = State.Round2; sequencePacket(jpake.round1().output()) }
            State.Round2 -> { state = State.Round3; sequencePacket(jpake.round2().output()) }
            State.Round3 -> {
                state = State.RequestAuth
                AuthAction.Write(authRequest(), jpake.round3().output())
            }

            State.RequestAuth -> {
                state = State.ChallengeReply
                val h = JPake.challengeHash(currentKey() ?: return AuthAction.Stalled, challenge)
                    ?: return AuthAction.Stalled
                AuthAction.Write(byteArrayOf(0x04) + h, null)
            }

            State.ChallengeReply -> { state = State.Unknown; AuthAction.Write(TIME_EXTENDED, null) }
            State.SendCertificate0 -> { state = State.SendCertificate1; AuthAction.Write(certInfo(0), null) }
            State.SendCertificate1 -> { state = State.SendCertificate1out; AuthAction.Write(null, certificates!!.partA) }
            State.SendCertificate1out -> { state = State.SendCertificate2; AuthAction.Write(certInfo(1), null) }
            State.SendCertificate2 -> { state = State.SendCertificate2out; AuthAction.Write(null, certificates!!.partB) }
            State.SendCertificate2out -> {
                state = State.SendKeyChallenge
                AuthAction.Write(byteArrayOf(0x0C) + randomBytes(16), null)
            }
            State.SendKeyChallenge -> {
                state = State.SendKeyChallengeOut
                AuthAction.Write(CHALLENGE_OUT, signedResponse)
            }
            State.SendKeyChallengeOut, State.Pairing -> {
                state = State.GetData
                AuthAction.Write(TIME_EXTENDED, null)
            }
            State.GetData, State.GetData2 -> { state = State.Unknown; AuthAction.ReadyForData }
            State.BondFailure -> { state = State.Unknown; AuthAction.BondFailure }
            else -> AuthAction.Stalled
        }
    }

    private fun certInfo(which: Int): ByteArray {
        val size = if (which == 0) certificates!!.partA.size else certificates!!.partB.size
        return le(6).put(0x0B).put(which.toByte()).putInt(size).array()
    }

    companion object {
        private const val SLOT: Byte = 2
        val TIME_EXTENDED = byteArrayOf(0x06, 0x19)
        private val TIME_EXTENDED2 = byteArrayOf(0xFF.toByte(), 0x06, 0x01)
        private val TIME_EXTENDED3 = byteArrayOf(0x06, 0x00)
        private val BOND_TRIGGERS = listOf(TIME_EXTENDED, TIME_EXTENDED2, TIME_EXTENDED3)
        private val CHALLENGE_OUT = byteArrayOf(0x0d, 0x00, 0x02)

        private val KEEP_ACCUMULATOR = setOf(
            State.SendCertificate1, State.SendCertificate1out,
            State.SendCertificate2, State.SendCertificate2out,
        )

        /** CertInfoRx: 0x0b state which size(u16 LE) ... (7 bytes). */
        internal fun certInfoSize(packet: ByteArray): Int? {
            if (packet.size != 7 || packet[0].toInt() != 0x0b) return null
            val b = java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.get()
            val st = b.get().toInt()
            val which = b.get().toInt()
            val size = b.short.toInt()
            return if (size > 0 && st == 0 && which >= 0) size else null
        }

        /** ECDSA-SHA256 over [challenge] with a PKCS#8 EC key; returns raw r||s (64 bytes). */
        fun signChallenge(pkcs8: ByteArray, challenge: ByteArray): ByteArray? = try {
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            val der = Signature.getInstance("SHA256withECDSA").run {
                initSign(key); update(challenge); sign()
            }
            derToRaw(der)
        } catch (e: Exception) {
            null
        }

        /** DER SEQUENCE { INTEGER r, INTEGER s } -> r(32) || s(32). */
        internal fun derToRaw(der: ByteArray): ByteArray {
            var i = 0
            require(der[i++].toInt() == 0x30)
            var len = der[i++].toInt() and 0xFF
            if (len and 0x80 != 0) i += len and 0x7F
            fun readInt(): BigInteger {
                require(der[i++].toInt() == 0x02)
                len = der[i++].toInt() and 0xFF
                val v = BigInteger(1, der.copyOfRange(i, i + len)); i += len
                return v
            }
            return readInt().toFixed(32) + readInt().toFixed(32)
        }
    }
}
