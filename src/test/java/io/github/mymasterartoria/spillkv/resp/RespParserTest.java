package io.github.mymasterartoria.spillkv.resp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mymasterartoria.spillkv.resp.RespValue.BulkString;
import io.github.mymasterartoria.spillkv.resp.RespValue.RespArray;
import io.github.mymasterartoria.spillkv.resp.RespValue.RespError;
import io.github.mymasterartoria.spillkv.resp.RespValue.RespInteger;
import io.github.mymasterartoria.spillkv.resp.RespValue.SimpleString;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RespParserTest {

    // ---------- helpers ----------

    /** Turns a readable literal into a buffer of wire bytes. */
    private static ByteBuffer wire(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Parses text that is expected to contain one complete value. */
    private static RespValue parseOne(String text) {
        Optional<RespValue> parsed = RespParser.tryParse(wire(text));
        assertTrue(parsed.isPresent(), () -> "expected a complete value from " + show(text));
        return parsed.get();
    }

    /**
     * Records hold the payload as a byte[], and arrays use identity equality, so
     * BulkString.equals does not compare contents. Always compare through this.
     */
    private static void assertBulk(String expected, RespValue actual) {
        BulkString bulk = assertInstanceOf(BulkString.class, actual);
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), bulk.value());
    }

    /** Makes CRLF visible in failure messages. */
    private static String show(String text) {
        return "\"" + text.replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    // ---------- the five types ----------

    @Nested
    @DisplayName("each RESP2 type")
    class Types {

        @Test
        void simpleString() {
            assertEquals(new SimpleString("OK"), parseOne("+OK\r\n"));
        }

        @Test
        void error() {
            assertEquals(new RespError("ERR unknown command"), parseOne("-ERR unknown command\r\n"));
        }

        @Test
        void integer() {
            assertEquals(new RespInteger(42), parseOne(":42\r\n"));
        }

        @Test
        void negativeInteger() {
            assertEquals(new RespInteger(-1), parseOne(":-1\r\n"));
        }

        @Test
        void bulkString() {
            assertBulk("hello", parseOne("$5\r\nhello\r\n"));
        }

        @Test
        @DisplayName("an empty bulk string still has its trailing CRLF")
        void emptyBulkString() {
            assertBulk("", parseOne("$0\r\n\r\n"));
        }

        @Test
        @DisplayName("$-1 is null, which is not the same as empty")
        void nullBulkString() {
            BulkString bulk = assertInstanceOf(BulkString.class, parseOne("$-1\r\n"));
            assertNull(bulk.value());
        }

        @Test
        @DisplayName("the length prefix means the payload may contain CRLF")
        void bulkStringIsBinarySafe() {
            // 4 bytes: 'a' CR LF 'b'. A parser that scans for the first CRLF gets this wrong.
            assertBulk("a\r\nb", parseOne("$4\r\na\r\nb\r\n"));
        }

        @Test
        void array() {
            RespArray array = assertInstanceOf(RespArray.class, parseOne("*2\r\n$3\r\nfoo\r\n:7\r\n"));
            assertEquals(2, array.items().size());
            assertBulk("foo", array.items().get(0));
            assertEquals(new RespInteger(7), array.items().get(1));
        }

        @Test
        void emptyArray() {
            RespArray array = assertInstanceOf(RespArray.class, parseOne("*0\r\n"));
            assertEquals(List.of(), array.items());
        }

        @Test
        void nullArray() {
            RespArray array = assertInstanceOf(RespArray.class, parseOne("*-1\r\n"));
            assertNull(array.items());
        }

        @Test
        void nestedArray() {
            RespArray outer = assertInstanceOf(RespArray.class, parseOne("*2\r\n*1\r\n:1\r\n+OK\r\n"));
            RespArray inner = assertInstanceOf(RespArray.class, outer.items().get(0));
            assertEquals(new RespInteger(1), inner.items().get(0));
            assertEquals(new SimpleString("OK"), outer.items().get(1));
        }

        @Test
        @DisplayName("a real command as redis-cli sends it")
        void commandArray() {
            RespArray array =
                    assertInstanceOf(
                            RespArray.class,
                            parseOne("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nhello\r\n"));
            assertEquals(3, array.items().size());
            assertBulk("SET", array.items().get(0));
            assertBulk("k", array.items().get(1));
            assertBulk("hello", array.items().get(2));
        }
    }

    // ---------- partial input ----------

    @Nested
    @DisplayName("incomplete input asks for more bytes instead of failing")
    class Incomplete {

        @Test
        void emptyBuffer() {
            assertEquals(Optional.empty(), RespParser.tryParse(wire("")));
        }

        @Test
        @DisplayName("cut at every single byte boundary, for every type")
        void cutAnywhere() {
            List<String> messages =
                    List.of(
                            "+OK\r\n",
                            "-ERR nope\r\n",
                            ":12345\r\n",
                            "$5\r\nhello\r\n",
                            "$0\r\n\r\n",
                            "$-1\r\n",
                            "$4\r\na\r\nb\r\n",
                            "*0\r\n",
                            "*-1\r\n",
                            "*2\r\n*1\r\n:1\r\n+OK\r\n",
                            "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nhello\r\n");

            for (String message : messages) {
                byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
                for (int cut = 0; cut < bytes.length; cut++) {
                    ByteBuffer partial = ByteBuffer.wrap(bytes, 0, cut).slice();
                    Optional<RespValue> parsed = RespParser.tryParse(partial);
                    assertEquals(
                            Optional.empty(),
                            parsed,
                            () ->
                                    "first "
                                            + partial.capacity()
                                            + " bytes of "
                                            + show(message)
                                            + " is not a whole value yet");
                }
                // and the whole thing does parse
                assertTrue(RespParser.tryParse(wire(message)).isPresent(), () -> show(message));
            }
        }

        @Test
        @DisplayName("the position is put back, so the same bytes can be re-parsed later")
        void positionIsRestored() {
            ByteBuffer buf = wire("*3\r\n$3\r\nSE");
            buf.position(0);
            assertEquals(Optional.empty(), RespParser.tryParse(buf));
            assertEquals(0, buf.position(), "an incomplete parse must not consume anything");
        }

        @Test
        @DisplayName("a half-finished element rolls the whole array back")
        void halfFinishedArrayElement() {
            ByteBuffer buf = wire("*2\r\n$3\r\nfoo\r\n$3\r\nb");
            assertEquals(Optional.empty(), RespParser.tryParse(buf));
            assertEquals(0, buf.position(), "the array is not done, so none of it was consumed");
        }
    }

    // ---------- pipelining ----------

    @Nested
    @DisplayName("pipelining")
    class Pipelining {

        @Test
        @DisplayName("two values in one buffer come out one call at a time")
        void twoValuesInOneBuffer() {
            ByteBuffer buf = wire("+OK\r\n:42\r\n");

            assertEquals(new SimpleString("OK"), RespParser.tryParse(buf).orElseThrow());
            assertEquals(new RespInteger(42), RespParser.tryParse(buf).orElseThrow());
            assertEquals(Optional.empty(), RespParser.tryParse(buf), "buffer should be drained");
        }

        @Test
        @DisplayName("a complete value followed by a partial one")
        void completeThenPartial() {
            ByteBuffer buf = wire("*1\r\n$4\r\nPING\r\n*1\r\n$4\r\nPI");

            assertInstanceOf(RespArray.class, RespParser.tryParse(buf).orElseThrow());
            int afterFirst = buf.position();
            assertEquals(Optional.empty(), RespParser.tryParse(buf));
            assertEquals(afterFirst, buf.position(), "the leftover must stay in the buffer");
        }

        @Test
        @DisplayName("the position lands exactly on the last byte consumed")
        void positionAfterAValue() {
            ByteBuffer buf = wire("+OK\r\nleftover");
            RespParser.tryParse(buf).orElseThrow();
            assertEquals(5, buf.position());
        }
    }

    // ---------- malformed input ----------

    @Nested
    @DisplayName("malformed input fails fast")
    class Malformed {

        @Test
        void unknownTypeByte() {
            assertThrows(RespProtocolException.class, () -> RespParser.tryParse(wire("%2\r\n")));
        }

        @Test
        void bulkLengthIsNotANumber() {
            assertThrows(RespProtocolException.class, () -> RespParser.tryParse(wire("$abc\r\n")));
        }

        @Test
        @DisplayName("-1 is the only negative bulk length there is")
        void bulkLengthIsNegativeButNotMinusOne() {
            assertThrows(RespProtocolException.class, () -> RespParser.tryParse(wire("$-2\r\n")));
        }

        @Test
        void arrayLengthIsNegativeButNotMinusOne() {
            assertThrows(RespProtocolException.class, () -> RespParser.tryParse(wire("*-2\r\n")));
        }

        @Test
        void integerIsNotANumber() {
            assertThrows(RespProtocolException.class, () -> RespParser.tryParse(wire(":abc\r\n")));
        }

        @Test
        @DisplayName("the payload must be followed by CRLF, not by anything else")
        void bulkPayloadNotTerminatedByCrlf() {
            // says 1 byte, then 'b' and CR where CRLF was promised
            assertThrows(RespProtocolException.class, () -> RespParser.tryParse(wire("$1\r\nab\r\n")));
        }
    }
}
