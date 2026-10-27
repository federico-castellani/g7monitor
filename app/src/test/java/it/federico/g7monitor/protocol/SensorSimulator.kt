package it.federico.g7monitor.protocol

/**
 * Minimal model of the sensor side of the G7 J-PAKE handshake, used to test the
 * phone side without hardware. Byte formats follow xDrip's keks plugin.
 */
class SensorSimulator(code: String, private val bonded: Boolean = true) {
    private val session = JPake.Session(code, me = JPake.SERVER, peer = JPake.CLIENT)
    private var incoming = ByteArray(0)
    private var challenge = ByteArray(8)
    var key: ByteArray? = null
        private set
    var authenticated = false
        private set

    /** Outputs produced by the sensor. */
    val authIndications = ArrayDeque<ByteArray>()
    val extraNotifications = ArrayDeque<ByteArray>()

    /** Certificate exchange (unbonded sensor): expected size of the client's cert, if any. */
    private var certExpected = 0
    private var certIndex = -1
    var certsReceived = 0
        private set
    var signatureVerified = false
        private set
    private var signChallenge = ByteArray(16)
    private val sensorCert = ByteArray(300) { (it * 7).toByte() }

    fun onExtraDataWrite(chunk: ByteArray) {
        incoming += chunk
        if (certExpected > 0 && incoming.size >= certExpected) {
            // Client certificate complete: answer with our own certificate on ExtraData.
            if (certIndex == 0) clientCertA = incoming.copyOf(certExpected)
            certsReceived++
            incoming = ByteArray(0)
            certExpected = 0
            sendExtra(sensorCert)
        }
    }

    private var clientCertA = ByteArray(0)

    /** Public key used to verify the client's challenge signature (test supplies it). */
    var clientPublicKey: java.security.PublicKey? = null

    private fun takeIncomingPacket(): JPake.Packet? {
        val p = JPake.Packet.parse(incoming)
        incoming = ByteArray(0)
        return p
    }

    private fun sendExtra(bytes: ByteArray) {
        bytes.toList().chunked(G7Gatt.CHUNK).forEach { extraNotifications += it.toByteArray() }
    }

    fun onAuthenticationWrite(cmd: ByteArray) {
        when (cmd[0].toInt()) {
            0x0A -> when (cmd[1].toInt()) {
                0 -> sendExtra(session.round1().output())
                1 -> {
                    session.peerPackets[1] = takeIncomingPacket()
                    check(session.validatePeerRound1()) { "client round 1 invalid" }
                    sendExtra(session.round2().output())
                }
                2 -> {
                    session.peerPackets[2] = takeIncomingPacket()
                    check(session.validatePeerRound2()) { "client round 2 invalid" }
                    sendExtra(session.round3().output())
                }
            }
            0x02 -> {
                if (incoming.isNotEmpty()) {
                    session.peerPackets[3] = takeIncomingPacket()
                    check(session.validatePeerRound3()) { "client round 3 invalid" }
                    key = session.sharedKey()
                }
                val token = cmd.copyOfRange(1, 9)
                challenge = randomBytes(8)
                val k = key ?: error("no key")
                authIndications += byteArrayOf(0x03) + JPake.challengeHash(k, token)!! + challenge
            }
            0x0B -> {
                certIndex = cmd[1].toInt()
                certExpected = java.nio.ByteBuffer.wrap(cmd, 2, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                incoming = ByteArray(0)
                val r = java.nio.ByteBuffer.allocate(7).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .put(0x0B).put(0).put(certIndex.toByte()).putShort(sensorCert.size.toShort()).put(0).put(0)
                authIndications += r.array()
            }
            0x0C -> {
                signChallenge = randomBytes(16)
                authIndications += byteArrayOf(0x0C, 0x00) + signChallenge
                sendExtra(ByteArray(64)) // sensor's own signature (ignored by the client)
            }
            0x0D -> {
                val sig = incoming
                incoming = ByteArray(0)
                signatureVerified = clientPublicKey?.let { key ->
                    java.security.Signature.getInstance("SHA256withECDSAinP1363Format").run {
                        initVerify(key); update(signChallenge); verify(sig)
                    }
                } ?: false
                authIndications += byteArrayOf(0x0D, 0x00)
            }
            0x06 -> authIndications += byteArrayOf(0x06, 0x00) // asks the phone to bond
            0x04 -> {
                val expected = JPake.challengeHash(key!!, challenge)!!
                authenticated = expected.contentEquals(cmd.copyOfRange(1, 9))
                authIndications += byteArrayOf(0x05, if (authenticated) 1 else 0, if (bonded) 1 else 2)
            }
        }
    }
}
