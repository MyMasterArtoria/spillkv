package io.github.mymasterartoria.spillkv.resp;

import java.util.List;

/**
 * One value in the RESP2 protocol.
 *
 * <p>The five cases are the five type bytes: {@code +} {@code -} {@code :} {@code $} {@code *}.
 * Three of them are named with a {@code Resp} prefix only because {@code Error}, {@code Integer}
 * and {@code Array} would collide with names from {@code java.lang} and {@code java.lang.reflect}.
 */
public sealed interface RespValue {

    /** {@code +OK\r\n} */
    record SimpleString(String value) implements RespValue {
    }

    /** {@code -ERR unknown command\r\n} */
    record RespError(String message) implements RespValue {
    }

    /** {@code :42\r\n} */
    record RespInteger(long value) implements RespValue {
    }

    /**
     * {@code $5\r\nhello\r\n}. The payload stays as raw bytes: RESP is binary safe, and keeping
     * bytes here is what lets the storage engine write a value straight to disk without decoding
     * it into a String first.
     *
     * <p>A {@code null} payload is the RESP null bulk string, {@code $-1\r\n}.
     */
    record BulkString(byte[] value) implements RespValue {
    }

    /**
     * {@code *2\r\n...}. A {@code null} list is the RESP null array, {@code *-1\r\n}; an empty
     * list is {@code *0\r\n}.
     */
    record RespArray(List<RespValue> items) implements RespValue {
    }
}
