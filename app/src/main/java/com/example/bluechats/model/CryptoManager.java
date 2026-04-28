package com.example.bluechats.model;

import android.util.Base64;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;

public class CryptoManager {
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private Ed25519PrivateKeyParameters signPrivateKey;
    private Ed25519PublicKeyParameters signPublicKey;
    private X25519PrivateKeyParameters ecdhPrivateKey;
    private X25519PublicKeyParameters ecdhPublicKey;
    private SecureRandom secureRandom;

    public CryptoManager() throws Exception {
        secureRandom = new SecureRandom();

        Ed25519KeyPairGenerator ed25519Gen = new Ed25519KeyPairGenerator();
        ed25519Gen.init(new Ed25519KeyGenerationParameters(secureRandom));
        AsymmetricCipherKeyPair signKeyPair = ed25519Gen.generateKeyPair();
        signPrivateKey = (Ed25519PrivateKeyParameters) signKeyPair.getPrivate();
        signPublicKey = (Ed25519PublicKeyParameters) signKeyPair.getPublic();

        X25519KeyPairGenerator x25519Gen = new X25519KeyPairGenerator();
        x25519Gen.init(new X25519KeyGenerationParameters(secureRandom));
        AsymmetricCipherKeyPair ecdhKeyPair = x25519Gen.generateKeyPair();
        ecdhPrivateKey = (X25519PrivateKeyParameters) ecdhKeyPair.getPrivate();
        ecdhPublicKey = (X25519PublicKeyParameters) ecdhKeyPair.getPublic();
    }

    public String getPublicKeyBase64() {
        return Base64.encodeToString(ecdhPublicKey.getEncoded(), Base64.NO_WRAP);
    }

    public String getSigningPublicKeyBase64() {
        return Base64.encodeToString(signPublicKey.getEncoded(), Base64.NO_WRAP);
    }

    public String encrypt(String plaintext, String recipientPublicKeyBase64) throws Exception {
        byte[] recipientPubBytes = Base64.decode(recipientPublicKeyBase64, Base64.NO_WRAP);
        X25519PublicKeyParameters recipientPub = new X25519PublicKeyParameters(recipientPubBytes, 0);

        X25519PrivateKeyParameters ephemeralPriv = new X25519PrivateKeyParameters(secureRandom);
        X25519PublicKeyParameters ephemeralPub = ephemeralPriv.generatePublicKey();

        X25519Agreement agreement = new X25519Agreement();
        agreement.init(ephemeralPriv);
        byte[] sharedSecret = new byte[32];
        agreement.calculateAgreement(recipientPub, sharedSecret, 0);

        byte[] derivedKey = hkdf(sharedSecret, "BlueChats-v1".getBytes());

        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        SecretKeySpec keySpec = new SecretKeySpec(derivedKey, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

        byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));

        byte[] result = new byte[32 + GCM_IV_LENGTH + ciphertext.length];
        System.arraycopy(ephemeralPub.getEncoded(), 0, result, 0, 32);
        System.arraycopy(iv, 0, result, 32, GCM_IV_LENGTH);
        System.arraycopy(ciphertext, 0, result, 32 + GCM_IV_LENGTH, ciphertext.length);

        return Base64.encodeToString(result, Base64.NO_WRAP);
    }

    public String decrypt(String encryptedBase64) throws Exception {
        byte[] encrypted = Base64.decode(encryptedBase64, Base64.NO_WRAP);

        byte[] ephemeralPubBytes = new byte[32];
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[encrypted.length - 32 - GCM_IV_LENGTH];

        System.arraycopy(encrypted, 0, ephemeralPubBytes, 0, 32);
        System.arraycopy(encrypted, 32, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(encrypted, 32 + GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

        X25519PublicKeyParameters ephemeralPub = new X25519PublicKeyParameters(ephemeralPubBytes, 0);

        X25519Agreement agreement = new X25519Agreement();
        agreement.init(ecdhPrivateKey);
        byte[] sharedSecret = new byte[32];
        agreement.calculateAgreement(ephemeralPub, sharedSecret, 0);

        byte[] derivedKey = hkdf(sharedSecret, "BlueChats-v1".getBytes());

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        SecretKeySpec keySpec = new SecretKeySpec(derivedKey, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, "UTF-8");
    }

    public String sign(byte[] data) throws Exception {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, signPrivateKey);
        signer.update(data, 0, data.length);
        byte[] signature = signer.generateSignature();
        return Base64.encodeToString(signature, Base64.NO_WRAP);
    }

    public boolean verify(String signerPublicKeyBase64, byte[] data, String signatureBase64) {
        try {
            byte[] pubKeyBytes = Base64.decode(signerPublicKeyBase64, Base64.NO_WRAP);
            Ed25519PublicKeyParameters pubKey = new Ed25519PublicKeyParameters(pubKeyBytes, 0);

            Ed25519Signer verifier = new Ed25519Signer();
            verifier.init(false, pubKey);
            verifier.update(data, 0, data.length);

            byte[] signature = Base64.decode(signatureBase64, Base64.NO_WRAP);
            return verifier.verifySignature(signature);
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] hkdf(byte[] inputKeyMaterial, byte[] info) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] prk = digest.digest(inputKeyMaterial);

        digest.reset();
        digest.update(prk);
        if (info != null) {
            digest.update(info);
        }
        digest.update(new byte[]{0x01});

        byte[] okm = digest.digest();
        byte[] result = new byte[32];
        System.arraycopy(okm, 0, result, 0, 32);
        return result;
    }
}
