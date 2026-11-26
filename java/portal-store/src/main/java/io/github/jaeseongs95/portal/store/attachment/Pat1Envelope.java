package io.github.jaeseongs95.portal.store.attachment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.app.attachment.AttachmentUnreadableException;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentId;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentRole;

/**
 * Single-blob binary envelope {@code PAT1} using JDK 25 {@code AES/GCM/NoPadding} (256-bit key, 12-byte IV, 128-bit authentication tag).
 *
 * <p>Binary layout: {@code [magic "PAT1" 4B][ver 1B (=1)][keyIdLen 1B][keyId UTF-8][iv 12B][ciphertext + 16B GCM tag]}.
 * Associated Authenticated Data (AAD) binds {@code ver}, {@code keyId}, {@code attachmentId}, and {@code role}.
 */
@Component
public class Pat1Envelope {

    static final byte[] MAGIC = {'P', 'A', 'T', '1'};
    static final byte VERSION = 1;
    static final int IV_BYTES = 12;
    static final int GCM_TAG_BITS = 128;
    static final int GCM_TAG_BYTES = GCM_TAG_BITS / 8;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final AttachmentKeyring keyring;
    private final SecureRandom random;

    public Pat1Envelope(AttachmentKeyring keyring, SecureRandom random) {
        this.keyring = Objects.requireNonNull(keyring, "keyring");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Encrypts {@code plaintext} into a {@code PAT1} binary envelope bound to {@code id} and {@code role}. */
    public byte[] encrypt(AttachmentId id, AttachmentRole role, byte[] plaintext) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(plaintext, "plaintext");
        AttachmentKeyring.KeyEntry active = keyring.activeKey();
        byte[] keyIdBytes = active.keyId().getBytes(StandardCharsets.UTF_8);
        if (keyIdBytes.length == 0 || keyIdBytes.length > 255) {
            throw new IllegalStateException("Key id length out of range: " + keyIdBytes.length);
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte[] aad = aad(VERSION, active.keyId(), id, role);
        byte[] sealed;
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, active.secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad);
            sealed = cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt attachment " + id.value(), e);
        }
        int headerLength = MAGIC.length + 1 + 1 + keyIdBytes.length + IV_BYTES;
        byte[] out = new byte[headerLength + sealed.length];
        int pos = 0;
        System.arraycopy(MAGIC, 0, out, pos, MAGIC.length);
        pos += MAGIC.length;
        out[pos++] = VERSION;
        out[pos++] = (byte) keyIdBytes.length;
        System.arraycopy(keyIdBytes, 0, out, pos, keyIdBytes.length);
        pos += keyIdBytes.length;
        System.arraycopy(iv, 0, out, pos, IV_BYTES);
        pos += IV_BYTES;
        System.arraycopy(sealed, 0, out, pos, sealed.length);
        return out;
    }

    /** Decrypts and authenticates a {@code PAT1} envelope for {@code id} and {@code role}. */
    public byte[] decrypt(AttachmentId id, AttachmentRole role, byte[] envelope) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(envelope, "envelope");
        int minHeader = MAGIC.length + 1 + 1 + 1 + IV_BYTES + GCM_TAG_BYTES;
        if (envelope.length < minHeader) {
            throw new AttachmentUnreadableException(id, role, AttachmentUnreadableException.Reason.CORRUPTED_ENVELOPE,
                "Attachment envelope is truncated (" + envelope.length + " bytes)");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (envelope[i] != MAGIC[i]) {
                throw new AttachmentUnreadableException(id, role, AttachmentUnreadableException.Reason.CORRUPTED_ENVELOPE,
                    "Attachment envelope has invalid magic header");
            }
        }
        byte ver = envelope[4];
        if (ver != VERSION) {
            throw new AttachmentUnreadableException(id, role, AttachmentUnreadableException.Reason.CORRUPTED_ENVELOPE,
                "Unsupported attachment envelope version: " + ver);
        }
        int keyIdLen = Byte.toUnsignedInt(envelope[5]);
        int ivOffset = 6 + keyIdLen;
        int cipherOffset = ivOffset + IV_BYTES;
        if (keyIdLen == 0 || envelope.length < cipherOffset + GCM_TAG_BYTES) {
            throw new AttachmentUnreadableException(id, role, AttachmentUnreadableException.Reason.CORRUPTED_ENVELOPE,
                "Attachment envelope key header is malformed");
        }
        String keyId = new String(envelope, 6, keyIdLen, StandardCharsets.UTF_8);
        Optional<SecretKey> key = keyring.findKey(keyId);
        if (key.isEmpty()) {
            throw new AttachmentUnreadableException(id, role, AttachmentUnreadableException.Reason.UNKNOWN_KEY,
                "Attachment key " + keyId + " was not found in keyring");
        }
        byte[] iv = Arrays.copyOfRange(envelope, ivOffset, cipherOffset);
        byte[] aad = aad(ver, keyId, id, role);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key.orElseThrow(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(envelope, cipherOffset, envelope.length - cipherOffset);
        } catch (AEADBadTagException e) {
            throw new AttachmentUnreadableException(id, role, AttachmentUnreadableException.Reason.AUTH_FAILED,
                "Attachment authentication failed (tampered ciphertext, header, or AAD mismatch)", e);
        } catch (GeneralSecurityException e) {
            throw new AttachmentUnreadableException(id, role, AttachmentUnreadableException.Reason.CORRUPTED_ENVELOPE,
                "Attachment decryption failed", e);
        }
    }

    private static byte[] aad(byte ver, String keyId, AttachmentId id, AttachmentRole role) {
        return ("PAT1|" + ver + "|" + keyId + "|" + id.value() + "|" + role.name()).getBytes(StandardCharsets.UTF_8);
    }
}
