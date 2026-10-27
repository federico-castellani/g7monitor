package it.federico.g7monitor.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class G7ProtocolTest {

    @Test
    fun p256MatchesJavaCrypto() {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        repeat(5) {
            val kp = gen.generateKeyPair()
            val d = (kp.private as java.security.interfaces.ECPrivateKey).s
            val w = (kp.public as ECPublicKey).w
            val q = P256.G * d
            assertEquals(w.affineX, q.x)
            assertEquals(w.affineY, q.y)
        }
        val a = P256.randomScalar()
        val b = P256.randomScalar()
        assertEquals(P256.G * a.add(b), P256.G * a + P256.G * b)
        assertEquals(P256.G * a, P256.G * a.add(b) - P256.G * b)
        assertTrue((P256.G * P256.n).isInfinity)
        val doubled = P256.G + P256.G
        assertEquals(P256.G * BigInteger.TWO, doubled)
    }

    @Test
    fun zkpRoundTrip() {
        val k = JPake.ScalarKeyPair.random()
        val p = JPake.prove(P256.G, k, JPake.CLIENT)
        assertTrue(JPake.verify(P256.G, p.point1, p.point2, p.hash, JPake.CLIENT))
        assertTrue(!JPake.verify(P256.G, p.point1, p.point2, p.hash, JPake.SERVER))
        val parsed = JPake.Packet.parse(p.output())!!
        assertEquals(p.point1, parsed.point1)
        assertEquals(p.hash, parsed.hash)
    }

    /** Pumps actions between the phone state machine and the simulated sensor. */
    private fun drive(auth: G7Auth, sim: SensorSimulator): List<AuthAction> {
        val terminal = mutableListOf<AuthAction>()
        val pending = ArrayDeque<AuthAction>()
        pending += auth.onConnected()
        var guard = 0
        while (guard++ < 200) {
            val a = pending.removeFirstOrNull()
            if (a != null) {
                when (a) {
                    is AuthAction.Write -> {
                        a.extraData?.toList()?.chunked(G7Gatt.CHUNK)?.forEach { sim.onExtraDataWrite(it.toByteArray()) }
                        a.command?.let { sim.onAuthenticationWrite(it) }
                    }
                    else -> terminal += a
                }
                continue
            }
            val extra = sim.extraNotifications.removeFirstOrNull()
            if (extra != null) { auth.onExtraData(extra)?.let { pending += it }; continue }
            val ind = sim.authIndications.removeFirstOrNull()
            if (ind != null) { pending += auth.onAuthenticationData(ind); continue }
            break
        }
        return terminal
    }

    @Test
    fun fullJPakeHandshake() {
        val auth = G7Auth("1234")
        val sim = SensorSimulator("1234", bonded = true)
        val result = drive(auth, sim)
        assertEquals(listOf<AuthAction>(AuthAction.ReadyForData), result)
        assertTrue(sim.authenticated)
        assertArrayEquals(sim.key, auth.sharedKey())
    }

    @Test
    fun reconnectWithSavedKeySkipsJPake() {
        val sim = SensorSimulator("4321")
        val first = G7Auth("4321")
        assertEquals(listOf<AuthAction>(AuthAction.ReadyForData), drive(first, sim))
        val key = first.sharedKey()!!

        // New app process: only the persisted key is available.
        val second = G7Auth("4321", savedKey = key)
        val start = second.onConnected() as AuthAction.Write
        assertNull(start.extraData)
        assertEquals(0x02, start.command!![0].toInt())
        sim.onAuthenticationWrite(start.command!!)
        val reply = second.onAuthenticationData(sim.authIndications.removeFirst()).single() as AuthAction.Write
        sim.onAuthenticationWrite(reply.command!!)
        assertEquals(listOf<AuthAction>(AuthAction.ReadyForData), second.onAuthenticationData(sim.authIndications.removeFirst()))
    }

    @Test
    fun wrongCodeFails() {
        val auth = G7Auth("1111")
        val sim = SensorSimulator("2222")
        val failed = try {
            drive(auth, sim).none { it == AuthAction.ReadyForData }
        } catch (e: IllegalStateException) {
            true // simulator rejected our round 3 proof
        }
        assertTrue(failed)
    }

    @Test
    fun unbondedSensorWithoutCertificatesReportsConfigError() {
        val result = drive(G7Auth("1234"), SensorSimulator("1234", bonded = false))
        assertTrue(result.single() is AuthAction.ConfigError)
    }

    @Test
    fun parsesGlucosePacket() {
        // opcode, status, clock=3600s, seq=12, ?, age=5s, glucose=123, state=Ok, trend=-1.2, predicted=118, info
        val p = le(19).put(0x4E).put(0).putInt(3600).putShort(12).putShort(0).putShort(5)
            .putShort(123).put(6).put((-12).toByte()).putShort(118).put(0).array()
        val g = G7Glucose.parse(p)!!
        assertEquals(123, g.glucoseMgdl)
        assertEquals(SensorState.Ok, g.state)
        assertEquals(-1.2, g.trendMgdlPerMin!!, 1e-9)
        assertEquals(118, g.predictedMgdl)
        assertEquals(10_000L - 5_000L, g.readingTime(10_000L))
        assertEquals(3600L, g.sensorClockSeconds)
    }

    @Test
    fun parsesBackfill() {
        val rec = le(18).putInt(300).putShort(140).put(6).put(0).put(10)
            .putInt(600).putShort(150).put(6).put(0).put(127).array()
        val list = G7BackfillRecord.parseStream(rec)
        assertEquals(2, list.size)
        assertEquals(150, list[1].glucoseMgdl)
        assertNull(list[1].trendMgdlPerMin)
    }

    @Test
    fun xdripHexMatches() {
        // xDrip's obfuscated constants for "client" / "server"
        assertArrayEquals("client".toByteArray(), xdripHexToBytes("36C69656E647"))
        assertArrayEquals("server".toByteArray(), xdripHexToBytes("375627675627"))
    }

    @Test
    fun challengeSignatureIsRawRS() {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = gen.generateKeyPair()
        val chal = randomBytes(16)
        val raw = G7Auth.signChallenge(kp.private.encoded, chal)
        assertNotNull(raw)
        assertEquals(64, raw!!.size)
        val ok = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(kp.public); update(chal); verify(raw)
        }
        assertTrue(ok)
    }

    @Test
    fun importsXdripGKeyQr() {
        val parts = listOf("keks_p1" to randomBytes(400), "keks_p2" to randomBytes(420), "keks_p3" to randomBytes(150))
        // Same serialization as xDrip QRcodeUtils.serializeBinaryPrefsMap + gzip + base64 (no padding)
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        d.writeShort(parts.size)
        for ((k, v) in parts) {
            val kb = ("b__" + k).toByteArray()
            d.writeShort(kb.size); d.write(kb); d.writeShort(v.size); d.write(v)
        }
        val gz = java.io.ByteArrayOutputStream().also { java.util.zip.GZIPOutputStream(it).use { z -> z.write(out.toByteArray()) } }
        val text = "xdp2:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(gz.toByteArray())
        val certs = G7Certificates.fromXdripQr(text)!!
        assertArrayEquals(parts[0].second, certs.partA)
        assertArrayEquals(parts[2].second, certs.partC)
        val (a, b, c) = certs.toHex()
        val again = G7Certificates.fromHex(a, b, c)!!
        assertArrayEquals(parts[1].second, again.partB)
        assertNull(G7Certificates.fromXdripQr("xdp2:not-valid"))
    }

    @Test
    fun unbondedSensorWithCertificatesReachesBonding() {
        // Test key pair taken from xDrip's keks Config (KEY1), stored there as nibble swapped hex.
        val pub = xdripHexToBytes("039503316070A26884ECD320106080A26884ECD330107030240040A08F620F921D6251ACB39895DB5C49AB30033E94D7E61C9EFE8F67107E3E0F5DC4E85FDB3EF05CCAA14C33C30D63F23D51F26A1624619087F06F06743031AEA9")!!
        val priv = xdripHexToBytes("03183920100003316070A26884ECD320106080A26884ECD33010704097037720101040025351C2844410F9815BE04794FBC0AF098652F1AF232648BADB684F3506FB30D10AA06080A26884ECD33010701A4430240040A08F620F921D6251ACB39895DB5C49AB30033E94D7E61C9EFE8F67107E3E0F5DC4E85FDB3EF05CCAA14C33C30D63F23D51F26A1624619087F06F06743031AEA9")!!
        val publicKey = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(pub))
        val certs = G7Certificates(randomBytes(500), randomBytes(450), priv)
        val sim = SensorSimulator("1234", bonded = false).apply { clientPublicKey = publicKey }
        val result = drive(G7Auth("1234", certificates = certs), sim)
        assertEquals(listOf<AuthAction>(AuthAction.CreateBond), result)
        assertEquals(2, sim.certsReceived)
        assertTrue(sim.signatureVerified)
    }

    @Test
    fun authRequestAlwaysUsesSlot2LikeXdrip() {
        val auth = G7Auth("1234", savedKey = ByteArray(16)).apply { locallyBonded = true }
        val cmd = (auth.onConnected() as AuthAction.Write).command!!
        assertEquals(0x02, cmd[0].toInt())
        assertEquals(10, cmd.size)
        assertEquals(2, cmd[9].toInt())
    }
}
