package it.federico.g7monitor.protocol

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Elliptic curve J-PAKE as used by the Dexcom G7 (port of xDrip's jamorham.keks.Calc).
 *
 * References: https://ia.cr/2010/190 and draft-cragie-tls-ecjpake-01.
 * The phone is "client", the sensor is "server".
 */
object JPake {
    val CLIENT = "client".toByteArray()
    val SERVER = "server".toByteArray()

    /** Each exchanged packet is 5 field elements: P1.x P1.y P2.x P2.y hash. */
    const val PACKET_SIZE = P256.FIELD_SIZE * 5

    class ScalarKeyPair(val privateKey: BigInteger, val publicKey: EcPoint) {
        companion object {
            fun random(): ScalarKeyPair {
                val d = P256.randomScalar()
                return ScalarKeyPair(d, P256.G * d)
            }
        }
    }

    /**
     * Wire packet. For round 1/2 packets: point1 = public key, point2 = ZKP commitment (V),
     * hash = ZKP response (r). Same layout as xDrip Packet.
     */
    class Packet(val hash: BigInteger, val point1: EcPoint, val point2: EcPoint) {
        fun output(): ByteArray = point1.rawXY() + point2.rawXY() + hash.toFixed(P256.FIELD_SIZE)

        companion object {
            fun parse(bytes: ByteArray): Packet? {
                if (bytes.size < PACKET_SIZE) return null
                val b = ByteBuffer.wrap(bytes)
                fun next(): BigInteger = ByteArray(P256.FIELD_SIZE).also { b.get(it) }.toUnsignedBigInteger()
                val p1 = EcPoint.of(next(), next()) ?: return null
                val p2 = EcPoint.of(next(), next()) ?: return null
                return Packet(next(), p1, p2)
            }
        }
    }

    private fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    private fun withLength(b: ByteArray): ByteArray = ByteBuffer.allocate(4).putInt(b.size).array() + b

    fun zkpHash(g: EcPoint, gv: EcPoint, gx: EcPoint, party: ByteArray): BigInteger =
        sha256(
            withLength(g.encoded()), withLength(gv.encoded()),
            withLength(gx.encoded()), withLength(party),
        ).toUnsignedBigInteger().mod(P256.n)

    /** Schnorr zero knowledge proof of knowledge of [key].privateKey for generator [g]. */
    fun prove(g: EcPoint, key: ScalarKeyPair, party: ByteArray): Packet {
        val v = P256.randomScalar()
        val gv = g * v
        val h = zkpHash(g, gv, key.publicKey, party)
        val r = v.subtract(h.multiply(key.privateKey)).mod(P256.n)
        return Packet(r, key.publicKey, gv)
    }

    fun verify(g: EcPoint, publicKey: EcPoint, gv: EcPoint, r: BigInteger, party: ByteArray): Boolean {
        if (publicKey.isInfinity) return false
        val h = zkpHash(g, gv, publicKey, party)
        return (g * r + publicKey * h) == gv
    }

    fun verifyPacket(g: EcPoint, p: Packet?, party: ByteArray): Boolean =
        p != null && verify(g, p.point1, p.point2, p.hash, party)

    /** Password bytes as xDrip: 6 character codes get a "00" prefix. */
    fun passwordBytes(code: String): ByteArray {
        val raw = code.toByteArray(Charsets.UTF_8)
        return if (code.length == 6) "00".toByteArray() + raw else raw
    }

    /**
     * One side of the exchange. [me]/[peer] are party identities; the phone uses
     * CLIENT/SERVER. (A sensor simulator in the unit tests uses the opposite.)
     */
    class Session(
        code: String,
        private val me: ByteArray = CLIENT,
        private val peer: ByteArray = SERVER,
    ) {
        val passwordBytes = passwordBytes(code)
        private val s = passwordBytes.toUnsignedBigInteger()
        val keyA = ScalarKeyPair.random()   // x1
        val keyB = ScalarKeyPair.random()   // x2

        /** Peer packets: index 1 = round 1 (X3), 2 = round 2 (X4), 3 = round 3 (B). */
        val peerPackets = arrayOfNulls<Packet>(4)

        fun reset() = peerPackets.fill(null)

        fun round1(): Packet = prove(P256.G, keyA, me)
        fun round2(): Packet = prove(P256.G, keyB, me)

        fun validatePeerRound1() = verifyPacket(P256.G, peerPackets[1], peer)
        fun validatePeerRound2() = verifyPacket(P256.G, peerPackets[2], peer)

        fun round3(): Packet {
            val x3 = peerPackets[1]!!.point1
            val x4 = peerPackets[2]!!.point1
            val x2s = keyB.privateKey.multiply(s).mod(P256.n)
            val g = keyA.publicKey + x3 + x4
            val a = g * x2s
            return prove(g, ScalarKeyPair(x2s, a), me)
        }

        fun validatePeerRound3(): Boolean {
            val p = peerPackets[3] ?: return false
            val g = keyA.publicKey + keyB.publicKey + peerPackets[1]!!.point1
            return verify(g, p.point1, p.point2, p.hash, peer)
        }

        /** 16 byte session key, or null when round 3 has not completed. */
        fun sharedKey(): ByteArray? {
            val b = peerPackets[3]?.point1 ?: return null
            val x4 = peerPackets[2]?.point1 ?: return null
            val x2 = keyB.privateKey
            val k = (b - x4 * x2.multiply(s).mod(P256.n)) * x2
            if (k.isInfinity) return null
            return sha256(k.x.toFixed(P256.FIELD_SIZE)).copyOf(16)
        }
    }

    /** AES-128-ECB(key, c || c), first 8 bytes. Used for both challenge directions. */
    fun challengeHash(key: ByteArray, challenge: ByteArray): ByteArray? {
        if (key.size != 16 || challenge.size != 8) return null
        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.doFinal(challenge + challenge).copyOf(8)
        } catch (e: Exception) {
            null
        }
    }
}
