package it.federico.g7monitor.protocol

import java.math.BigInteger

/**
 * Minimal NIST P-256 (secp256r1) arithmetic, dependency free.
 *
 * J-PAKE needs raw point addition / subtraction / scalar multiplication, which the
 * standard Java/Android crypto APIs do not expose. xDrip uses BouncyCastle for this;
 * doing it here keeps the APK small. Internally uses Jacobian coordinates.
 *
 * Note: not constant time. That is acceptable for this use (short lived J-PAKE
 * ephemeral keys on the user's own phone) but don't reuse it for anything else.
 */
object P256 {
    val p = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    val b = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    /** Group order (called Q in xDrip's code). */
    val n = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    val G = EcPoint(
        BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16),
        BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16),
    )
    const val FIELD_SIZE = 32

    private val THREE = BigInteger.valueOf(3)

    /** Random scalar in [1, n-1]. */
    fun randomScalar(): BigInteger {
        while (true) {
            val k = BigInteger(256, secureRandom)
            if (k.signum() > 0 && k < n) return k
        }
    }

    fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        val lhs = y.multiply(y).mod(p)
        val rhs = x.multiply(x).multiply(x).subtract(THREE.multiply(x)).add(b).mod(p)
        return lhs == rhs
    }

    // ---- Jacobian internals ----
    internal class J(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        val isInfinity get() = z.signum() == 0
    }

    private val INF = J(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    internal fun toJ(pt: EcPoint): J = if (pt.isInfinity) INF else J(pt.x, pt.y, BigInteger.ONE)

    internal fun fromJ(j: J): EcPoint {
        if (j.isInfinity) return EcPoint.INFINITY
        val zInv = j.z.modInverse(p)
        val zInv2 = zInv.multiply(zInv).mod(p)
        val x = j.x.multiply(zInv2).mod(p)
        val y = j.y.multiply(zInv2).multiply(zInv).mod(p)
        return EcPoint(x, y)
    }

    // dbl-2001-b (a = -3)
    internal fun dbl(q: J): J {
        if (q.isInfinity || q.y.signum() == 0) return INF
        val delta = q.z.multiply(q.z).mod(p)
        val gamma = q.y.multiply(q.y).mod(p)
        val beta = q.x.multiply(gamma).mod(p)
        val alpha = THREE.multiply(q.x.subtract(delta)).multiply(q.x.add(delta)).mod(p)
        val x3 = alpha.multiply(alpha).subtract(beta.shiftLeft(3)).mod(p)
        val z3 = q.y.add(q.z).pow(2).subtract(gamma).subtract(delta).mod(p)
        val y3 = alpha.multiply(beta.shiftLeft(2).subtract(x3))
            .subtract(gamma.multiply(gamma).shiftLeft(3)).mod(p)
        return J(x3, y3, z3)
    }

    // add-2007-bl
    internal fun add(a: J, c: J): J {
        if (a.isInfinity) return c
        if (c.isInfinity) return a
        val z1z1 = a.z.multiply(a.z).mod(p)
        val z2z2 = c.z.multiply(c.z).mod(p)
        val u1 = a.x.multiply(z2z2).mod(p)
        val u2 = c.x.multiply(z1z1).mod(p)
        val s1 = a.y.multiply(c.z).multiply(z2z2).mod(p)
        val s2 = c.y.multiply(a.z).multiply(z1z1).mod(p)
        val h = u2.subtract(u1).mod(p)
        val r = s2.subtract(s1).shiftLeft(1).mod(p)
        if (h.signum() == 0) {
            return if (r.signum() == 0) dbl(a) else INF
        }
        val i = h.shiftLeft(1).pow(2).mod(p)
        val jj = h.multiply(i).mod(p)
        val v = u1.multiply(i).mod(p)
        val x3 = r.multiply(r).subtract(jj).subtract(v.shiftLeft(1)).mod(p)
        val y3 = r.multiply(v.subtract(x3)).subtract(s1.multiply(jj).shiftLeft(1)).mod(p)
        val z3 = a.z.add(c.z).pow(2).subtract(z1z1).subtract(z2z2).multiply(h).mod(p)
        return J(x3, y3, z3)
    }

    internal fun mul(pt: EcPoint, k: BigInteger): EcPoint {
        val e = k.mod(n)
        if (e.signum() == 0 || pt.isInfinity) return EcPoint.INFINITY
        val base = toJ(pt)
        var acc = INF
        for (i in e.bitLength() - 1 downTo 0) {
            acc = dbl(acc)
            if (e.testBit(i)) acc = add(acc, base)
        }
        return fromJ(acc)
    }
}

/** Affine point on P-256. */
class EcPoint internal constructor(
    val x: BigInteger,
    val y: BigInteger,
    val isInfinity: Boolean = false,
) {
    operator fun plus(o: EcPoint): EcPoint = P256.fromJ(P256.add(P256.toJ(this), P256.toJ(o)))
    operator fun unaryMinus(): EcPoint = if (isInfinity) this else EcPoint(x, P256.p.subtract(y).mod(P256.p))
    operator fun minus(o: EcPoint): EcPoint = this + (-o)
    operator fun times(k: BigInteger): EcPoint = P256.mul(this, k)

    /** Uncompressed SEC1 encoding (0x04 || X || Y), as BouncyCastle getEncoded(false). */
    fun encoded(): ByteArray =
        if (isInfinity) byteArrayOf(0) else byteArrayOf(4) + x.toFixed(32) + y.toFixed(32)

    /** X || Y, 64 bytes (xDrip JECPoint.toBytes). */
    fun rawXY(): ByteArray = x.toFixed(32) + y.toFixed(32)

    override fun equals(other: Any?): Boolean =
        other is EcPoint && other.isInfinity == isInfinity && (isInfinity || (other.x == x && other.y == y))

    override fun hashCode(): Int = if (isInfinity) 0 else x.hashCode() * 31 + y.hashCode()

    companion object {
        val INFINITY = EcPoint(BigInteger.ZERO, BigInteger.ZERO, true)

        /** Validated construction: returns null when the coordinates are not on the curve. */
        fun of(x: BigInteger, y: BigInteger): EcPoint? = if (P256.isOnCurve(x, y)) EcPoint(x, y) else null
    }
}

/** Big endian unsigned, left padded to [len] bytes (BouncyCastle asUnsignedByteArray). */
fun BigInteger.toFixed(len: Int): ByteArray {
    val raw = toByteArray()
    if (raw.size == len) return raw
    val out = ByteArray(len)
    if (raw.size > len) {
        // strip sign byte
        System.arraycopy(raw, raw.size - len, out, 0, len)
    } else {
        System.arraycopy(raw, 0, out, len - raw.size, raw.size)
    }
    return out
}

fun ByteArray.toUnsignedBigInteger(): BigInteger = BigInteger(1, this)
