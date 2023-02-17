package com.github.serezhka.airplay.lib.internal;

import lombok.extern.slf4j.Slf4j;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import net.i2p.crypto.eddsa.Utils;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;
import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;

@Slf4j
public class Pairing {

    private final KeyPair keyPair;

    private byte[] edTheirs;
    private byte[] ecdhOurs;
    private byte[] ecdhTheirs;
    private byte[] ecdhSecret;

    private boolean pairVerified;

    public Pairing() {
        this.keyPair = new KeyPairGenerator().generateKeyPair();
    }

    public void pairSetup(OutputStream out) throws IOException {
        out.write(((EdDSAPublicKey) keyPair.getPublic()).getAbyte());
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void pairVerify(InputStream request, OutputStream response) throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidAlgorithmParameterException, InvalidKeyException, SignatureException, BadPaddingException, IllegalBlockSizeException, IOException {
        int flag = request.read();
        request.skip(3);
        if (flag > 0) {
            request.read(ecdhTheirs = new byte[32]);
            request.read(edTheirs = new byte[32]);

            Curve25519 curve25519 = Curve25519.getInstance(Curve25519.BEST);
            Curve25519KeyPair curve25519KeyPair = curve25519.generateKeyPair();

            ecdhOurs = curve25519KeyPair.getPublicKey();
            ecdhSecret = curve25519.calculateAgreement(ecdhTheirs, curve25519KeyPair.getPrivateKey());
            log.info("Shared secret: " + Utils.bytesToHex(ecdhSecret));

            Cipher aesCtr128Encrypt = initCipher();

            byte[] dataToSign = new byte[64];
            System.arraycopy(ecdhOurs, 0, dataToSign, 0, 32);
            System.arraycopy(ecdhTheirs, 0, dataToSign, 32, 32);

            EdDSAEngine edDSAEngine = new EdDSAEngine();
            edDSAEngine.initSign(keyPair.getPrivate());
            byte[] signature = edDSAEngine.signOneShot(dataToSign);

            byte[] encryptedSignature = aesCtr128Encrypt.doFinal(signature);

            byte[] responseContent = new byte[ecdhOurs.length + encryptedSignature.length];
            System.arraycopy(ecdhOurs, 0, responseContent, 0, ecdhOurs.length);
            System.arraycopy(encryptedSignature, 0, responseContent, ecdhOurs.length, encryptedSignature.length);

            response.write(responseContent);
        } else {
            byte[] signature = new byte[64];
            request.read(signature);

            Cipher aesCtr128Encrypt = initCipher();

            byte[] sigBuffer = new byte[64];
            aesCtr128Encrypt.update(sigBuffer);
            sigBuffer = aesCtr128Encrypt.doFinal(signature);

            byte[] sigMessage = new byte[64];
            System.arraycopy(ecdhTheirs, 0, sigMessage, 0, 32);
            System.arraycopy(ecdhOurs, 0, sigMessage, 32, 32);

            EdDSAEngine edDSAEngine = new EdDSAEngine();
            EdDSAPublicKey edDSAPublicKey = new EdDSAPublicKey(new EdDSAPublicKeySpec(edTheirs, EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)));
            edDSAEngine.initVerify(edDSAPublicKey);

            pairVerified = edDSAEngine.verifyOneShot(sigMessage, sigBuffer);
            log.info("Pair verified: " + pairVerified);
        }
    }

    public boolean isPairVerified() {
        return pairVerified;
    }

    public byte[] getSharedSecret() {
        return ecdhSecret;
    }

    private Cipher initCipher() throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidAlgorithmParameterException, InvalidKeyException {
        MessageDigest sha512Digest = MessageDigest.getInstance("SHA-512");
        sha512Digest.update("Pair-Verify-AES-Key".getBytes(StandardCharsets.UTF_8));
        sha512Digest.update(ecdhSecret);
        byte[] sharedSecretSha512AesKey = Arrays.copyOfRange(sha512Digest.digest(), 0, 16);

        sha512Digest.update("Pair-Verify-AES-IV".getBytes(StandardCharsets.UTF_8));
        sha512Digest.update(ecdhSecret);
        byte[] sharedSecretSha512AesIV = Arrays.copyOfRange(sha512Digest.digest(), 0, 16);

        Cipher aesCtr128Encrypt = Cipher.getInstance("AES/CTR/NoPadding");
        aesCtr128Encrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sharedSecretSha512AesKey, "AES"), new IvParameterSpec(sharedSecretSha512AesIV));
        return aesCtr128Encrypt;
    }
}
