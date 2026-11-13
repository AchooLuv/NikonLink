package com.achooluv.nikonlink.data

/**
 * How the phone reaches the camera.
 *
 * [AP]  – the camera hosts its own access point and the phone joins it.
 * [STA] – the camera joins an existing network (router / phone hotspot) and the
 *         phone reaches it over the LAN; the phone keeps its normal internet.
 */
enum class ConnectMode(val key: String, val shortLabel: String) {
    AP(key = "ap", shortLabel = "相机热点"),
    STA(key = "sta", shortLabel = "同一 Wi-Fi");

    companion object {
        fun from(value: String?): ConnectMode =
            entries.firstOrNull { it.key == value } ?: AP
    }
}
