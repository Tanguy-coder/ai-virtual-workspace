package dev.aiw.shared;

import java.security.SecureRandom;

/** ULID generator: 48-bit millisecond timestamp + 80 random bits, Crockford base32, lexicographically sortable. */
public final class Ids {
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {}

    public static String newId() {
        long time = System.currentTimeMillis();
        byte[] rnd = new byte[10];
        RANDOM.nextBytes(rnd);
        char[] out = new char[26];
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        // 80 random bits -> 16 base32 chars
        long hi = 0;
        for (int i = 0; i < 5; i++) hi = (hi << 8) | (rnd[i] & 0xFF);
        long lo = 0;
        for (int i = 5; i < 10; i++) lo = (lo << 8) | (rnd[i] & 0xFF);
        for (int i = 17; i >= 10; i--) {
            out[i] = ALPHABET[(int) (hi & 31)];
            hi >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ALPHABET[(int) (lo & 31)];
            lo >>>= 5;
        }
        return new String(out);
    }
}
