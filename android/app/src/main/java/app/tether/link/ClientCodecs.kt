package app.tether.link

import java.util.concurrent.ConcurrentHashMap

/**
 * The codecs each client said it decodes, by its static key, across its links.
 *
 * A new link starts with G.711 only until the client's `sync` names its codecs. Galaxy A54 with an iPhone, 2026-11-28:
 * the link dropped mid-call and the call's audio moved to the iPhone's new link 17 ms after it came up, before its
 * `sync`, so the phone announced G.711 at 8 kHz while its helper kept capturing at 16 kHz (the call's Opus rate). The
 * iPhone played the 16 kHz frames at half speed and threw half away (`drained 8826 ms` of 434 frames, `drift 996668
 * ppm`), and the phone took the iPhone's 8 kHz voice as 16 kHz (inject clock 12443 Hz). Now a client back on a new link
 * keeps the codecs of its earlier one ([known]), and a sync that brings a codec at the helper's rate announces the
 * call's audio again ([reannounce]).
 */
class ClientCodecs {
    private val byKey = ConcurrentHashMap<String, List<String>>()

    fun remember(key: String?, codecs: List<String>) { if (key != null && codecs.isNotEmpty()) byKey[key] = codecs }

    /** What this client decoded on its earlier link, or null for a client not seen yet. */
    fun known(key: String?): List<String>? = key?.let { byKey[it] }

    companion object {
        /**
         * After a client's sync: announce the running call audio again ([wanted] instead of [running]) when the client now
         * gets a different codec and that codec runs at the helper's rate (a rate change would need a helper restart).
         */
        fun reannounce(running: String?, wanted: String, wantedRate: Int, helperRate: Int): Boolean =
            running != null && running != wanted && wantedRate == helperRate
    }
}
