package com.cloudbridge.app.core;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The cryptography of the MEGA protocol. MEGA encrypts on the client: keys travel as arrays of big-endian
 * 32-bit words ("a32"), names are AES-CBC encrypted JSON, and file data is AES-CTR with a chunked CBC-MAC.
 */
final class MegaCrypto {
    private MegaCrypto() {
    }

    // ---------------------------------------------------------------- encodings

    /** Big-endian 32-bit words; the input is zero-padded to a multiple of 4 bytes. */
    static int[] strToA32(byte[] b) {
        int words = (b.length + 3) / 4;
        int[] out = new int[words];
        for (int i = 0; i < words; i++) {
            int v = 0;
            for (int j = 0; j < 4; j++) {
                int idx = i * 4 + j;
                v = (v << 8) | (idx < b.length ? b[idx] & 0xff : 0);
            }
            out[i] = v;
        }
        return out;
    }

    static byte[] a32ToBytes(int[] a) {
        byte[] out = new byte[a.length * 4];
        for (int i = 0; i < a.length; i++) {
            out[i * 4] = (byte) (a[i] >>> 24);
            out[i * 4 + 1] = (byte) (a[i] >>> 16);
            out[i * 4 + 2] = (byte) (a[i] >>> 8);
            out[i * 4 + 3] = (byte) a[i];
        }
        return out;
    }

    /** MEGA's base64: URL-safe alphabet, no padding. */
    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] unb64(String s) {
        String t = s.replace('+', '-').replace('/', '_').replace(",", "").trim();
        return Base64.getUrlDecoder().decode(t);
    }

    static int[] b64ToA32(String s) {
        return strToA32(unb64(s));
    }

    static String a32ToB64(int[] a) {
        return b64(a32ToBytes(a));
    }

    // ---------------------------------------------------------------- AES helpers

    private static Cipher ecb(int[] key, boolean encrypt) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(a32ToBytes(key), "AES"));
            return c;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Encrypts or decrypts each 4-word block on its own (how MEGA wraps keys). */
    static int[] cryptKeys(int[] words, int[] key, boolean encrypt) {
        Cipher c = ecb(key, encrypt);
        int whole = words.length / 4 * 4;
        byte[] in = a32ToBytes(Arrays.copyOf(words, whole));
        try {
            return strToA32(c.doFinal(in));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static int[] encryptKey(int[] words, int[] key) {
        return cryptKeys(words, key, true);
    }

    static int[] decryptKey(int[] words, int[] key) {
        return cryptKeys(words, key, false);
    }

    /** AES-CBC with an all-zero IV (attributes). Data must be a multiple of 16 bytes. */
    static byte[] cbc(byte[] data, int[] key, boolean encrypt) {
        try {
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(a32ToBytes(key), "AES"),
                    new javax.crypto.spec.IvParameterSpec(new byte[16]));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- account keys

    /** Old (version 1) account: the password is stretched with 65536 rounds of AES. */
    static int[] prepareKey(byte[] password) {
        int[] arr = strToA32(password);
        int chunks = (arr.length + 3) / 4;
        Cipher[] ciphers = new Cipher[chunks];
        for (int j = 0; j < chunks; j++) {
            int[] key = new int[4];
            for (int i = 0; i < 4; i++) {
                if (j * 4 + i < arr.length) key[i] = arr[j * 4 + i];
            }
            ciphers[j] = ecb(key, true);
        }
        byte[] pkey = a32ToBytes(new int[]{0x93C467E3, 0x7DB0C7A4, 0xD1BE3F81, 0x0152CB56});
        try {
            for (int r = 0; r < 0x10000; r++) {
                for (Cipher c : ciphers) pkey = c.doFinal(pkey);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        return strToA32(pkey);
    }

    /** Old (version 1) account: the login proof sent instead of the password. */
    static String stringHash(String s, int[] aesKey) {
        int[] s32 = strToA32(s.getBytes(StandardCharsets.UTF_8));
        int[] h = new int[4];
        for (int i = 0; i < s32.length; i++) h[i % 4] ^= s32[i];
        Cipher c = ecb(aesKey, true);
        byte[] b = a32ToBytes(h);
        try {
            for (int r = 0; r < 0x4000; r++) b = c.doFinal(b);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        int[] out = strToA32(b);
        return a32ToB64(new int[]{out[0], out[2]});
    }

    /** PBKDF2-HMAC-SHA512 (own implementation: identical on every Android version). */
    static byte[] pbkdf2Sha512(byte[] password, byte[] salt, int iterations, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(password.length == 0 ? new byte[1] : password, "HmacSHA512"));
            int hLen = mac.getMacLength();
            byte[] out = new byte[length];
            for (int block = 1, pos = 0; pos < length; block++) {
                mac.update(salt);
                mac.update(new byte[]{(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block});
                byte[] u = mac.doFinal();
                byte[] t = u.clone();
                for (int i = 1; i < iterations; i++) {
                    u = mac.doFinal(u);
                    for (int k = 0; k < hLen; k++) t[k] ^= u[k];
                }
                int n = Math.min(hLen, length - pos);
                System.arraycopy(t, 0, out, pos, n);
                pos += n;
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- RSA session id

    /** An MPI is a 2-byte bit length followed by that many bits, big-endian. Returns the value and the next offset. */
    static BigInteger readMpi(byte[] b, int[] offset) {
        int bits = ((b[offset[0]] & 0xff) << 8) | (b[offset[0] + 1] & 0xff);
        int len = (bits + 7) / 8;
        byte[] mag = Arrays.copyOfRange(b, offset[0] + 2, offset[0] + 2 + len);
        offset[0] += 2 + len;
        return new BigInteger(1, mag);
    }

    static byte[] writeMpi(BigInteger v) {
        byte[] mag = v.toByteArray();
        int start = 0;
        while (start < mag.length - 1 && mag[start] == 0) start++;
        byte[] out = new byte[2 + mag.length - start];
        int bits = v.bitLength();
        out[0] = (byte) (bits >>> 8);
        out[1] = (byte) bits;
        System.arraycopy(mag, start, out, 2, mag.length - start);
        return out;
    }

    /** Decrypts the session id MEGA sends at login (csid) with the account's RSA private key (privk). */
    static String sessionId(String csid, byte[] privateKey) {
        int[] off = {0};
        BigInteger p = readMpi(privateKey, off);
        BigInteger q = readMpi(privateKey, off);
        BigInteger d = readMpi(privateKey, off);
        byte[] enc = unb64(csid);
        BigInteger c = readMpi(enc, new int[]{0});
        byte[] m = c.modPow(d, p.multiply(q)).toByteArray();
        int start = 0;
        while (start < m.length - 1 && m[start] == 0) start++;
        int len = Math.min(43, m.length - start);
        return b64(Arrays.copyOfRange(m, start, start + len));
    }

    // ---------------------------------------------------------------- names

    /** "MEGA{...}" zero-padded and encrypted with the node's key. */
    static String encryptAttrs(String name, int[] aesKey) {
        byte[] plain = ("MEGA{\"n\":" + Json.quote(name) + "}").getBytes(StandardCharsets.UTF_8);
        byte[] padded = Arrays.copyOf(plain, (plain.length + 15) / 16 * 16);
        return b64(cbc(padded, aesKey, true));
    }

    /** The name inside encrypted attributes, or null when they cannot be read. */
    static String decryptName(String attr, int[] aesKey) {
        try {
            byte[] raw = unb64(attr);
            if (raw.length == 0 || raw.length % 16 != 0) return null;
            byte[] plain = cbc(raw, aesKey, false);
            int end = plain.length;
            while (end > 0 && plain[end - 1] == 0) end--;
            String text = new String(plain, 0, end, StandardCharsets.UTF_8);
            if (!text.startsWith("MEGA{")) return null;
            return Json.str(Json.parseObject(text.substring(4), "MEGA"), "n");
        } catch (RuntimeException | CloudException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- file data

    /** Chunk boundaries MEGA uses: 128 KB, 256 KB ... 1 MB, then 1 MB each. */
    static long nextChunkSize(long current) {
        return current < 0x100000 ? current + 0x20000 : current;
    }

    /** AES-CTR for file data; the counter block is the 64-bit nonce followed by a 64-bit block counter. */
    static Cipher ctr(int[] aesKey, int[] nonce) {
        try {
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            byte[] iv = a32ToBytes(new int[]{nonce[0], nonce[1], 0, 0});
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(a32ToBytes(aesKey), "AES"), new javax.crypto.spec.IvParameterSpec(iv));
            return c;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The chunked CBC-MAC over the plaintext of a file. Feed it every chunk in order. */
    static final class FileMac {
        private final Cipher aes;
        private final int[] nonce;
        private int[] file = new int[4];

        FileMac(int[] aesKey, int[] nonce) {
            this.aes = ecb(aesKey, true);
            this.nonce = nonce;
        }

        void chunk(byte[] data, int len) {
            int[] mac = {nonce[0], nonce[1], nonce[0], nonce[1]};
            byte[] block = new byte[16];
            try {
                for (int i = 0; i < len; i += 16) {
                    int n = Math.min(16, len - i);
                    Arrays.fill(block, (byte) 0);
                    System.arraycopy(data, i, block, 0, n);
                    int[] w = strToA32(block);
                    for (int k = 0; k < 4; k++) mac[k] ^= w[k];
                    mac = strToA32(aes.doFinal(a32ToBytes(mac)));
                }
                for (int k = 0; k < 4; k++) file[k] ^= mac[k];
                file = strToA32(aes.doFinal(a32ToBytes(file)));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        int[] meta() {
            return new int[]{file[0] ^ file[1], file[2] ^ file[3]};
        }
    }
}
