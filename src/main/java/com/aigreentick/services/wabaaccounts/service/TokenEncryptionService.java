package com.aigreentick.services.wabaaccounts.service;

import com.aigreentick.services.wabaaccounts.exception.InvalidRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;


@Component
@Slf4j
public class TokenEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH_BYTES = 12;     // 96-bit nonce (GCM standard)
    private static final int GCM_TAG_LENGTH_BITS  = 128;   // 128-bit auth tag (GCM max)
    private static final String ENCRYPTED_PREFIX   = "ENC:";

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * @param encryptionKeyHex  32-byte AES key as hex string (64 hex chars)
     *                          Set via TOKEN_ENCRYPTION_KEY env var.
     *
     *                          Example: TOKEN_ENCRYPTION_KEY=a3f1...64hexchars...9b2c
     */
    public TokenEncryptionService(
            @Value("${token.encryption.key}") String encryptionKeyHex) {

        if (encryptionKeyHex == null || encryptionKeyHex.isBlank()) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY is not set. " +
                            "Generate one with: openssl rand -hex 32\n" +
                            "Then set env var: TOKEN_ENCRYPTION_KEY=<64 hex chars>");
        }
        if (encryptionKeyHex.length() != 64) {
            throw new IllegalStateException(
                    "TOKEN_ENCRYPTION_KEY must be a 32-byte key encoded as 64 hex chars. " +
                            "Current length: " + encryptionKeyHex.length() + " chars. " +
                            "Generate with: openssl rand -hex 32");
        }
        byte[] keyBytes = hexToBytes(encryptionKeyHex);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        log.info("TokenEncryptionService initialized with AES-256-GCM");
    }

    // ════════════════════════════════════════════════════════════
    // PUBLIC API
    // ════════════════════════════════════════════════════════════

    /**
     * Encrypt a plaintext token before storing in DB.
     *
     * Returns: "ENC:<base64(iv + ciphertext)>"
     *
     * Safe to call multiple times on the same input — each call
     * produces a different ciphertext due to the random IV.
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new InvalidRequestException("Cannot encrypt null or blank token");
        }
        try {
            // Generate fresh random IV for every encryption
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Prepend IV to ciphertext — store as single Base64 blob
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(combined);

        } catch (Exception ex) {
            // Never log the plaintext token on failure
            throw new InvalidRequestException("Token encryption failed: " + ex.getMessage());
        }
    }

    /**
     * Decrypt a stored token for use in API calls.
     *
     * Backward compatible: if the value doesn't start with "ENC:",
     * it's a legacy plaintext token — return as-is so old tokens
     * still work without a forced migration.
     *
     * Log a warning for plaintext tokens so you can track migration progress.
     */
    public String decrypt(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            throw new InvalidRequestException("Cannot decrypt null or blank stored token");
        }

        // Backward compatibility: legacy plaintext tokens
        if (!storedValue.startsWith(ENCRYPTED_PREFIX)) {
            log.warn("Decrypting legacy plaintext token — schedule DB re-encryption. " +
                    "Run: POST /api/v1/admin/migrate-token-encryption");
            return storedValue;
        }

        try {
            String base64Data = storedValue.substring(ENCRYPTED_PREFIX.length());
            byte[] combined = Base64.getDecoder().decode(base64Data);

            // Split IV and ciphertext
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);

        } catch (javax.crypto.AEADBadTagException ex) {
            // This means the ciphertext was tampered with, or the key is wrong
            throw new InvalidRequestException(
                    "Token decryption failed: authentication tag mismatch. " +
                            "Possible causes: wrong encryption key, or data corruption.");
        } catch (Exception ex) {
            throw new InvalidRequestException("Token decryption failed: " + ex.getMessage());
        }
    }

    /**
     * True if this stored value is encrypted (has the ENC: prefix).
     * Used for migration checks.
     */
    public boolean isEncrypted(String storedValue) {
        return storedValue != null && storedValue.startsWith(ENCRYPTED_PREFIX);
    }

    // ════════════════════════════════════════════════════════════
    // PRIVATE
    // ════════════════════════════════════════════════════════════

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}