package io.github.mymasterartoria.spillkv.resp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.mymasterartoria.spillkv.resp.RespValue.BulkString;
import io.github.mymasterartoria.spillkv.resp.RespValue.RespArray;
import io.github.mymasterartoria.spillkv.resp.RespValue.RespError;
import io.github.mymasterartoria.spillkv.resp.RespValue.RespInteger;
import io.github.mymasterartoria.spillkv.resp.RespValue.SimpleString;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RespEncoderTest {

    // ---------- helpers ----------

    private static BulkString bulk(String text) {
        return new BulkString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertEncodes(String expectedWire, RespValue value) {
        byte[] expected = expectedWire.getBytes(StandardCharsets.UTF_8);
        byte[] actual = RespEncoder.encode(value);
        assertArrayEquals(
                expected,
                actual,
                () -> "expected " + show(expected) + " but got " + show(actual));
    }

    /** Makes CRLF visible in failure messages. */
    private static String show(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return "\"" + text.replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    // ---------- scalars ----------

    @Nested
    @DisplayName("scalar types")
    class Scalars {

        @Test
        void simpleString() {
            assertEncodes("+OK\r\n", new SimpleString("OK"));
        }

        @Test
        void error() {
            assertEncodes("-ERR unknown command\r\n", new RespError("ERR unknown command"));
        }

        @Test
        void integer() {
            assertEncodes(":42\r\n", new RespInteger(42));
        }

        @Test
        void zero() {
            assertEncodes(":0\r\n", new RespInteger(0));
        }

        @Test
        void negativeInteger() {
            assertEncodes(":-1\r\n", new RespInteger(-1));
        }

        @Test
        @DisplayName("the full 64-bit range, since INCR works on signed 64-bit integers")
        void extremeIntegers() {
            assertEncodes(":9223372036854775807\r\n", new RespInteger(Long.MAX_VALUE));
            assertEncodes(":-9223372036854775808\r\n", new RespInteger(Long.MIN_VALUE));
        }
    }

    // ---------- bulk strings ----------

    @Nested
    @DisplayName("bulk strings")
    class Bulk {

        @Test
        void plain() {
            assertEncodes("$5\r\nhello\r\n", bulk("hello"));
        }

        @Test
        @DisplayName("empty is length 0 and still ends with CRLF")
        void empty() {
            assertEncodes("$0\r\n\r\n", bulk(""));
        }

        @Test
        @DisplayName("null is $-1 with nothing after it")
        void nullBulk() {
            assertEncodes("$-1\r\n", new BulkString(null));
        }

        @Test
        @DisplayName("a payload containing CRLF is written as-is")
        void binarySafe() {
            assertEncodes("$4\r\na\r\nb\r\n", bulk("a\r\nb"));
        }

        @Test
        @DisplayName("the length is in bytes, not in characters")
        void lengthCountsBytesNotChars() {
            // "héllo" is 5 characters but 6 bytes in UTF-8 (é takes two).
            // Using String.length() for the prefix gets this wrong.
            assertEncodes("$6\r\nhéllo\r\n", bulk("héllo"));
        }

        @Test
        @DisplayName("bytes that are not valid text survive untouched")
        void arbitraryBytes() {
            byte[] payload = {0, (byte) 0xFF, (byte) 0x80, 13, 10};
            byte[] actual = RespEncoder.encode(new BulkString(payload));

            byte[] header = "$5\r\n".getBytes(StandardCharsets.US_ASCII);
            byte[] expected = new byte[header.length + payload.length + 2];
            System.arraycopy(header, 0, expected, 0, header.length);
            System.arraycopy(payload, 0, expected, header.length, payload.length);
            expected[expected.length - 2] = '\r';
            expected[expected.length - 1] = '\n';

            assertArrayEquals(expected, actual);
        }
    }

    // ---------- arrays ----------

    @Nested
    @DisplayName("arrays")
    class Arrays_ {

        @Test
        void arrayOfBulkStrings() {
            assertEncodes(
                    "*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n",
                    new RespArray(List.of(bulk("foo"), bulk("bar"))));
        }

        @Test
        @DisplayName("elements of mixed types")
        void mixed() {
            assertEncodes(
                    "*3\r\n+OK\r\n:7\r\n$-1\r\n",
                    new RespArray(
                            List.of(new SimpleString("OK"), new RespInteger(7), new BulkString(null))));
        }

        @Test
        void empty() {
            assertEncodes("*0\r\n", new RespArray(List.of()));
        }

        @Test
        @DisplayName("null is *-1, not *0")
        void nullArray() {
            assertEncodes("*-1\r\n", new RespArray(null));
        }

        @Test
        void nested() {
            assertEncodes(
                    "*2\r\n*1\r\n:1\r\n+OK\r\n",
                    new RespArray(
                            List.of(
                                    new RespArray(List.of(new RespInteger(1))),
                                    new SimpleString("OK"))));
        }

        @Test
        @DisplayName("the reply CONFIG GET sends, which redis-benchmark needs at startup")
        void configGetReply() {
            assertEncodes(
                    "*2\r\n$4\r\nsave\r\n$0\r\n\r\n",
                    new RespArray(List.of(bulk("save"), bulk(""))));
        }
    }

    // ---------- round trip ----------

    @Nested
    @DisplayName("encode then parse gives back the same value")
    class RoundTrip {

        private void assertRoundTrips(RespValue original) {
            ByteBuffer wire = ByteBuffer.wrap(RespEncoder.encode(original));
            RespValue parsed = RespParser.tryParse(wire).orElseThrow();
            assertSameValue(original, parsed);
            assertEquals(0, wire.remaining(), "the parser should consume every encoded byte");
        }

        /** Structural equality that compares byte[] payloads by content. */
        private void assertSameValue(RespValue expected, RespValue actual) {
            switch (expected) {
                case BulkString e -> {
                    BulkString a = assertInstanceOf(BulkString.class, actual);
                    if (e.value() == null) {
                        assertNull(a.value());
                    } else {
                        assertArrayEquals(e.value(), a.value());
                    }
                }
                case RespArray e -> {
                    RespArray a = assertInstanceOf(RespArray.class, actual);
                    if (e.items() == null) {
                        assertNull(a.items());
                        return;
                    }
                    assertEquals(e.items().size(), a.items().size());
                    for (int i = 0; i < e.items().size(); i++) {
                        assertSameValue(e.items().get(i), a.items().get(i));
                    }
                }
                default -> assertEquals(expected, actual);
            }
        }

        @Test
        void everyScalar() {
            assertRoundTrips(new SimpleString("PONG"));
            assertRoundTrips(new RespError("WRONGTYPE nope"));
            assertRoundTrips(new RespInteger(Long.MIN_VALUE));
            assertRoundTrips(bulk("héllo"));
            assertRoundTrips(bulk(""));
            assertRoundTrips(new BulkString(null));
        }

        @Test
        void aCommandAsAClientWouldSendIt() {
            assertRoundTrips(new RespArray(List.of(bulk("SET"), bulk("k"), bulk("a\r\nb"))));
        }

        @Test
        void deepNesting() {
            RespValue v = new RespInteger(1);
            for (int i = 0; i < 50; i++) {
                v = new RespArray(List.of(v, bulk("x")));
            }
            assertRoundTrips(v);
        }

        @Test
        void largePayload() {
            byte[] big = new byte[1 << 20];
            Arrays.fill(big, (byte) 'z');
            assertRoundTrips(new BulkString(big));
        }
    }
}
