package com.hubidubitask.core.peer

import org.json.JSONObject

/**
 * The pairing exchange, both halves. The phone always connects to the Mac
 * (the Mac listens on the LAN); the code may have been shown on either.
 *
 *   phone -> mac  {deviceId, name, nonce, proof = HMAC(code, "phone", nonce, fp, deviceId)}
 *   mac  -> phone {deviceId, name, token, nonce2,
 *                  proof = HMAC(code, "mac", nonce, nonce2, fp, token, deviceId)}
 *
 * `fp` is the Mac's certificate fingerprint as the phone sees it. A machine in
 * the middle shows the phone a different certificate, so the Mac's check of
 * the phone's proof fails; and without the code it can't forge the answer.
 * The phone then pins that fingerprint and keeps the token for every sync.
 */
data class PairRequest(val deviceId: String, val name: String, val nonce: String, val proof: String) {
    fun json() = JSONObject().put("deviceId", deviceId).put("name", name).put("nonce", nonce)
        .put("proof", proof).toString()
    companion object {
        fun of(s: String) = JSONObject(s).let {
            PairRequest(it.getString("deviceId"), it.optString("name"), it.getString("nonce"), it.getString("proof"))
        }
    }
}

data class PairResponse(val deviceId: String, val name: String, val token: String,
                        val nonce: String, val proof: String) {
    fun json() = JSONObject().put("deviceId", deviceId).put("name", name).put("token", token)
        .put("nonce", nonce).put("proof", proof).toString()
    companion object {
        fun of(s: String) = JSONObject(s).let {
            PairResponse(it.getString("deviceId"), it.optString("name"), it.getString("token"),
                it.getString("nonce"), it.getString("proof"))
        }
    }
}

/** The phone's half. */
object PairingClient {
    fun request(code: String, deviceId: String, name: String, seenFingerprint: String): PairRequest {
        val nonce = PeerPairing.nonce()
        return PairRequest(deviceId, name, nonce,
            PeerPairing.proof(code, "phone", nonce, seenFingerprint, deviceId))
    }

    /** True when the answer really came from the Mac holding the same code. */
    fun verify(code: String, req: PairRequest, resp: PairResponse, seenFingerprint: String): Boolean =
        PeerPairing.same(resp.proof, PeerPairing.proof(code, "mac", req.nonce, resp.nonce,
            seenFingerprint, resp.token, resp.deviceId))
}

/**
 * The Mac's half: one code at a time, open for [PeerPairing.CODE_TTL_MS], and
 * shut after [PeerPairing.MAX_ATTEMPTS] wrong proofs so it can't be guessed
 * online. A pairing attempt while no code is open is simply refused and does
 * not count — the phone may be polling for the Mac while you type the code.
 */
class PairingDesk(private val clock: () -> Long = System::currentTimeMillis) {
    private var code: String? = null
    private var openedAt = 0L
    private var failures = 0

    @Synchronized fun open(code: String) { this.code = code; openedAt = clock(); failures = 0 }
    @Synchronized fun close() { code = null }
    @Synchronized fun isOpen() = code != null && clock() - openedAt < PeerPairing.CODE_TTL_MS

    sealed interface Outcome {
        data class Paired(val response: PairResponse) : Outcome
        data object NotOpen : Outcome
        data object Rejected : Outcome
    }

    @Synchronized
    fun answer(req: PairRequest, myFingerprint: String, myId: String, myName: String): Outcome {
        val c = code?.takeIf { isOpen() } ?: return Outcome.NotOpen
        val expected = PeerPairing.proof(c, "phone", req.nonce, myFingerprint, req.deviceId)
        if (!PeerPairing.same(req.proof, expected)) {
            if (++failures >= PeerPairing.MAX_ATTEMPTS) code = null
            return Outcome.Rejected
        }
        code = null   // one code, one pairing
        val token = PeerPairing.token()
        val nonce = PeerPairing.nonce()
        return Outcome.Paired(PairResponse(myId, myName, token, nonce,
            PeerPairing.proof(c, "mac", req.nonce, nonce, myFingerprint, token, myId)))
    }
}
