package in.oneeq.securepdf.service;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * AES-256-GCM helpers.
 *
 * <p>Wire format written per encrypted page is:
 * <pre>  IV (12 bytes) || ciphertext || GCM tag (16 bytes)  </pre>
 * The tag is appended to the ciphertext by the JCE, which matches exactly what
 * the browser's WebCrypto {@code AES-GCM} expects, so the same bytes decrypt in
 * both Java and JavaScript with no format juggling.
 */
@Service
public class CryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    private static final int IV_LENGTH = 12;      // 96-bit nonce, the GCM sweet spot
    private static final int TAG_BITS = 128;      // 16-byte authentication tag

    private final SecureRandom random = new SecureRandom();

    /** Generate a fresh per-book AES-256 key. */
    public SecretKey generateKey() {
        try {
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(KEY_BITS);
            return gen.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate AES key", e);
        }
    }

    public SecretKey keyFromBytes(byte[] raw) {
        return new SecretKeySpec(raw, "AES");
    }

    /**
     * Encrypt {@code plaintext} and return {@code IV || ciphertext || tag}.
     * A fresh random IV is used for every call — critical for GCM safety.
     */
    public byte[] encrypt(SecretKey key, byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ctWithTag = cipher.doFinal(plaintext);

            byte[] out = new byte[iv.length + ctWithTag.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ctWithTag, 0, out, iv.length, ctWithTag.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }
}
