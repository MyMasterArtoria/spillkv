package io.github.mymasterartoria.spillkv.resp;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Reads RESP2 values out of a byte buffer that is being filled from a socket.
 *
 * <p>
 * This is deliberately free of any Netty types so that it can be unit tested on
 * plain byte arrays. The contract below is the one Netty's
 * {@code ByteToMessageDecoder} expects, so wiring it up later is mechanical.
 */
public final class RespParser {

    private RespParser() {
    }

    /**
     * Tries to read exactly one value starting at the buffer's current
     * position.
     *
     * <ul>
     * <li>A complete value: returns it, and leaves the position just past its
     * last byte, so that calling this again reads the next value (this is what
     * makes pipelining work).
     * <li>Not enough bytes yet: returns {@link Optional#empty()} and restores
     * the position to where it was on entry, so the caller can append more
     * bytes and try the whole thing again. This is not an error.
     * <li>Bytes that cannot be RESP2 whatever arrives next: throws
     * {@link RespProtocolException}.
     * </ul>
     *
     * @param buf bytes received so far, positioned at the start of the next
     * value
     * @return the value, or empty if more bytes are needed
     */
    public static Optional<RespValue> tryParse(ByteBuffer buf) {

        int start = buf.position();
        RespValue result = parseValue(buf);
        if (result == null) {
            buf.position(start);
            return Optional.empty();
        }
        
        return Optional.of(result);
    }

    /**
     * Returns null when there are not enough bytes yet.
     */
    private static RespValue parseValue(ByteBuffer buf) {
        return null;  
       }
}
