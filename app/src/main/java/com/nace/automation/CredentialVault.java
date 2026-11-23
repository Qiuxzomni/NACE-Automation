package com.nace.automation;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.ProviderException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CredentialVault {
    private static final String KEY_ALIAS = "nace.remembered_sign_in.v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte FORMAT_VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;
    private static final Object LOCK = new Object();

    static final class Credentials {
        final String phoneNumber;
        final String password;

        Credentials(String phoneNumber, String password) {
            this.phoneNumber = phoneNumber;
            this.password = password;
        }
    }

    private final AtomicFile file;

    CredentialVault(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "remembered-sign-in"));
    }

    Credentials load(ExchangeLink link) {
        synchronized (LOCK) {
            byte[] payload = null;
            try {
                byte[] record = file.readFully();
                int headerSize = 1 + IV_BYTES;
                if (record.length < headerSize + TAG_BYTES + Integer.BYTES + 2
                        || record.length > headerSize + TAG_BYTES + MAX_PAYLOAD_BYTES
                        || record[0] != FORMAT_VERSION) return null;

                SecretKey key = readKey(openKeyStore());
                if (key == null) return null;
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, record, 1, IV_BYTES));
                cipher.updateAAD(link.origin.getBytes(StandardCharsets.UTF_8));
                payload = cipher.doFinal(record, headerSize, record.length - headerSize);

                ByteBuffer data = ByteBuffer.wrap(payload);
                int phoneLength = data.getInt();
                if (phoneLength <= 0 || phoneLength >= data.remaining()) return null;
                return new Credentials(new String(payload, Integer.BYTES, phoneLength, StandardCharsets.UTF_8),
                        new String(payload, Integer.BYTES + phoneLength, data.remaining() - phoneLength,
                                StandardCharsets.UTF_8));
            } catch (IOException | GeneralSecurityException | ProviderException e) {
                // A different origin, unreadable file, or unavailable key requires a new sign-in.
                return null;
            } finally {
                if (payload != null) Arrays.fill(payload, (byte) 0);
            }
        }
    }

    // The caller must verify that these credentials signed in successfully on this origin.
    boolean saveVerified(ExchangeLink link, String phoneNumber, String password) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty() || password == null || password.isEmpty()) return false;
        synchronized (LOCK) {
            byte[] phoneBytes = phoneNumber.getBytes(StandardCharsets.UTF_8);
            byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
            byte[] payload = null;
            FileOutputStream stream = null;
            try {
                long payloadSize = (long) Integer.BYTES + phoneBytes.length + passwordBytes.length;
                if (payloadSize > MAX_PAYLOAD_BYTES) return false;
                payload = ByteBuffer.allocate((int) payloadSize).putInt(phoneBytes.length)
                        .put(phoneBytes).put(passwordBytes).array();

                Cipher cipher = createEncryptingCipher();
                cipher.updateAAD(link.origin.getBytes(StandardCharsets.UTF_8));
                byte[] iv = cipher.getIV();
                if (iv.length != IV_BYTES) return false;
                byte[] ciphertext = cipher.doFinal(payload);
                stream = file.startWrite();
                stream.write(FORMAT_VERSION);
                stream.write(iv);
                stream.write(ciphertext);
                file.finishWrite(stream);
                return true;
            } catch (IOException | GeneralSecurityException | ProviderException e) {
                if (stream != null) file.failWrite(stream);
                return false;
            } finally {
                Arrays.fill(phoneBytes, (byte) 0);
                Arrays.fill(passwordBytes, (byte) 0);
                if (payload != null) Arrays.fill(payload, (byte) 0);
            }
        }
    }

    void clear() {
        synchronized (LOCK) {
            file.delete();
            try {
                openKeyStore().deleteEntry(KEY_ALIAS);
            } catch (IOException | GeneralSecurityException | ProviderException ignored) {
                // Removing the stored ciphertext also removes the remembered sign-in.
            }
        }
    }

    private static KeyStore openKeyStore() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }

    private static SecretKey readKey(KeyStore store) throws GeneralSecurityException {
        return (SecretKey) store.getKey(KEY_ALIAS, null);
    }

    private static SecretKey generateKey() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Scheduled reauthentication needs the key while the screen is locked.
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }

    private static Cipher createEncryptingCipher() throws GeneralSecurityException, IOException {
        KeyStore store = openKeyStore();
        SecretKey key = readKey(store);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key == null ? generateKey() : key);
        } catch (KeyPermanentlyInvalidatedException e) {
            store.deleteEntry(KEY_ALIAS);
            cipher.init(Cipher.ENCRYPT_MODE, generateKey());
        }
        return cipher;
    }
}
