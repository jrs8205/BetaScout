package android.util;

import java.nio.charset.StandardCharsets;

/** JVM stub for android.util.Base64, delegating to java.util.Base64. */
public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    public static final int NO_CLOSE = 16;

    private Base64() {}

    public static byte[] encode(byte[] input, int flags) {
        java.util.Base64.Encoder enc = ((flags & URL_SAFE) != 0)
                ? java.util.Base64.getUrlEncoder()
                : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) {
            enc = enc.withoutPadding();
        }
        return enc.encode(input);
    }

    public static byte[] encode(byte[] input, int offset, int len, int flags) {
        byte[] slice = new byte[len];
        System.arraycopy(input, offset, slice, 0, len);
        return encode(slice, flags);
    }

    public static String encodeToString(byte[] input, int flags) {
        return new String(encode(input, flags), StandardCharsets.US_ASCII);
    }

    public static String encodeToString(byte[] input, int offset, int len, int flags) {
        return new String(encode(input, offset, len, flags), StandardCharsets.US_ASCII);
    }

    public static byte[] decode(String str, int flags) {
        return decode(str.getBytes(StandardCharsets.US_ASCII), flags);
    }

    public static byte[] decode(byte[] input, int flags) {
        String s = new String(input, StandardCharsets.US_ASCII).trim();
        boolean urlSafe = (flags & URL_SAFE) != 0 || s.indexOf('-') >= 0 || s.indexOf('_') >= 0;
        int rem = s.length() % 4;
        if (rem != 0) {
            StringBuilder sb = new StringBuilder(s);
            for (int i = rem; i < 4; i++) {
                sb.append('=');
            }
            s = sb.toString();
        }
        java.util.Base64.Decoder dec = urlSafe
                ? java.util.Base64.getUrlDecoder()
                : java.util.Base64.getMimeDecoder();
        return dec.decode(s);
    }
}
