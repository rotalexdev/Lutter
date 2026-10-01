package dev.rotalex.lutter.serialization.storage

/**
 * Bytes at a location (PLAN §18.3).
 *
 * Movement only: the codec owns what the bytes mean, so implementations never parse.
 * Suspended for backends that cannot answer inline; the contract stays bytes in, bytes out.
 */
public interface DocumentStorage {
    /** Bytes at [location]; fails when nothing is stored there. */
    public suspend fun read(location: String): ByteArray

    /** [bytes] at [location], replacing whatever was there. */
    public suspend fun write(location: String, bytes: ByteArray)
}
