package io.github.mymasterartoria.spillkv.resp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Turns RESP2 values back into wire bytes.
 *
 * <p>
 * Unlike parsing, there is no partial-input case here: the value is always
 * complete, so encoding cannot run out of bytes.
 */
public final class RespEncoder {

    private RespEncoder() {
    }

    /**
     * Encodes one value, including everything nested inside it.
     *
     * @return the exact bytes a RESP2 peer expects on the wire
     */
    public static byte[] encode(RespValue value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(value, out);
        return out.toByteArray();
    }

    private static void write(RespValue value, ByteArrayOutputStream out) {
        switch (value) {
            case RespValue.SimpleString s ->
                writeLine(out, '+', s.value());
            case RespValue.RespError e ->
                writeLine(out, '-', e.message());
            case RespValue.RespInteger i ->
                writeLine(out, ':', Long.toString(i.value()));
            case RespValue.BulkString b -> {
                byte[] data = b.value();
                if (data == null) {                 // Null Bulk String
                    writeLine(out, '$', "-1");
                } else {
                    writeLine(out, '$', Integer.toString(data.length));
                    out.write(data, 0, data.length);
                    out.write('\r');
                    out.write('\n');
                }
            }
            case RespValue.RespArray a -> {
                if (a.items() == null) {         // Null Array
                    writeLine(out, '*', "-1");
                } else {
                    writeLine(out, '*', Integer.toString(a.items().size()));
                    for (RespValue element : a.items()) {
                        write(element, out);        
                    }
                }
            }
        }
    }

    /**
     * Writes prefix, then text as ASCII, then CRLF.
     */
    private static void writeLine(ByteArrayOutputStream out, char prefix, String text) {
        out.write(prefix);
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
        out.write('\r');
        out.write('\n');
    }
}
