package io.github.mymasterartoria.spillkv.resp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.mymasterartoria.spillkv.resp.RespValue.RespError;
import io.github.mymasterartoria.spillkv.resp.RespValue.RespInteger;
import io.github.mymasterartoria.spillkv.resp.RespValue.SimpleString;

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
        if (!buf.hasRemaining()) {
            return null;
        }

        byte type = buf.get();

        return switch (type) {
            case '+' -> {
                String line = readLine(buf);
                yield line == null ? null : new SimpleString(line);
            }
            case '-' -> {
                String line = readLine(buf);
                yield line == null ? null : new RespError(line);
            }
            case ':' -> {
                String line = readLine(buf);
                yield line == null ? null : new RespInteger(parseNumber(line));
            }
            case '$' -> {

                String line = readLine(buf);
                if (line == null) {
                    yield null;
                }
                long length = parseNumber(line);
                if (length == -1) {
                    yield new RespValue.BulkString(null);
                }
                if (length < -1) {
                    throw new RespProtocolException("invalid bulk string length: " + length);
                }
                if (buf.remaining() < length + 2) {
                    yield null;
                }
                byte[] payload = new byte[(int) length];
                buf.get(payload);
                if (buf.get() != '\r' || buf.get() != '\n') {
                    throw new RespProtocolException("bulk string not terminated with CRLF");
                }
                yield new RespValue.BulkString(payload);
            }
            case '*' -> {
                
                String line = readLine(buf);
                if (line == null) {
                    yield null;}
                long count = parseNumber(line);
                if (count == -1) {
                    yield new RespValue.RespArray(null);
                }
                if (count < -1) {
                    throw new RespProtocolException("invalid array length: " + count);
                }
                List<RespValue> items = new ArrayList<>();
                for (long i = 0; i < count; i++) {
                    RespValue item = parseValue(buf);
                    if (item == null) {
                        yield null;
                    }
                    items.add(item);
                }
                yield new RespValue.RespArray(List.copyOf(items));
            }
            
            default ->
                throw new RespProtocolException("unknown type byte: " + (char) type);
        };
    }

    /**
     * Reads up to the next CRLF and returns the text before it, or null if no
     * CRLF has arrived.
     */
    private static String readLine(ByteBuffer buf) {
        int start = buf.position();
        int limit = buf.limit();
        for (int i = start; i < limit - 1; i++) {
            if (buf.get(i) == '\r' && buf.get(i + 1) == '\n') {
                int length = i - start;
                byte[] bytes = new byte[length];
                for (int j = 0; j < length; j++) {
                    bytes[j] = buf.get(start + j);
                }
                buf.position(i + 2);
                return new String(bytes, StandardCharsets.US_ASCII);
            }
        }

        return null;
    }

    private static long parseNumber(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new RespProtocolException("not a number: " + text);
        }
    }

}
