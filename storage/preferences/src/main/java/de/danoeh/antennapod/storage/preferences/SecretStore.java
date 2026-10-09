package de.danoeh.antennapod.storage.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores secrets encrypted with a key that never leaves the Android Keystore, in a preferences file of its own.
 * The ciphertext is useless without this device's keystore, e.g. if the file is copied or read over adb.
 */
final class SecretStore {
    private static final String TAG = "SecretStore";
    private static final String FILE = "secrets";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "antennapod_secrets";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private final SharedPreferences prefs;

    SecretStore(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Returns the secret, or "" if it is not set or cannot be decrypted (e.g. restored onto another device).
     */
    @NonNull
    String get(String name) {
        String stored = prefs.getString(name, null);
        if (stored == null) {
            return "";
        }
        try {
            String[] parts = stored.split(":", 2);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Could not decrypt " + name + ": " + e.getClass().getSimpleName());
            return "";
        }
    }

    /**
     * Stores the secret, or removes it when empty. Returns false if the keystore is unavailable.
     */
    boolean set(String name, String value) {
        if (value == null || value.isEmpty()) {
            prefs.edit().remove(name).apply();
            return true;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            prefs.edit().putString(name, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(ciphertext, Base64.NO_WRAP)).apply();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Could not encrypt " + name + ": " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
