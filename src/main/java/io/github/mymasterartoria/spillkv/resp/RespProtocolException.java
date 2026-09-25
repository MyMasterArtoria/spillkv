package io.github.mymasterartoria.spillkv.resp;

/** Thrown when the bytes on the wire cannot be RESP2, no matter what arrives next. */
public class RespProtocolException extends RuntimeException {

    public RespProtocolException(String message) {
        super(message);
    }
}
