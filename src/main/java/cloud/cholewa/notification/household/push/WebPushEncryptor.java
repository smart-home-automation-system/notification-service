package cloud.cholewa.notification.household.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * The body of a Web Push message, encrypted for one browser (RFC 8291, content coding {@code aes128gcm}
 * of RFC 8188), with the JDK alone: ECDH on P-256, HKDF with HMAC-SHA-256, AES-128-GCM. The library the
 * task named first (nl.martijndwars:web-push) brings BouncyCastle and posts with a blocking client of its
 * own - twenty lines of the JDK do the same and stay on the reactive path.
 * <p>
 * One record, as every push service takes: the message must fit 4096 bytes with its 16 bytes of tag and
 * the delimiter, so a plaintext above {@link #MAX_PLAINTEXT} is refused rather than cut.
 * <p>
 * Every message gets a new key pair and a new salt, so the same text never encrypts the same twice.
 */
final class WebPushEncryptor {

    static final int RECORD_SIZE = 4096;
    private static final int TAG_LENGTH = 16;
    static final int MAX_PLAINTEXT = RECORD_SIZE - TAG_LENGTH - 1;
    private static final int SALT_LENGTH = 16;
    private static final int AUTH_LENGTH = 16;
    private static final byte LAST_RECORD = 0x02;

    private static final byte[] KEY_INFO = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    private final Supplier<KeyPair> keyPairs;
    private final Supplier<byte[]> salts;

    WebPushEncryptor() {
        this(WebPushEncryptor::newKeyPair, WebPushEncryptor::newSalt);
    }

    //for the test vector of RFC 8291, which fixes the key pair of the sender and the salt
    WebPushEncryptor(final Supplier<KeyPair> keyPairs, final Supplier<byte[]> salts) {
        this.keyPairs = keyPairs;
        this.salts = salts;
    }

    /**
     * @param plaintext what the browser reads, at most {@link #MAX_PLAINTEXT} bytes
     * @param p256dh    the public key of the browser, base64url, as its subscription gives it
     * @param auth      the authentication secret of the browser, base64url
     */
    byte[] encrypt(final byte[] plaintext, final String p256dh, final String auth) throws GeneralSecurityException {
        if (plaintext.length > MAX_PLAINTEXT) {
            throw new GeneralSecurityException("A Web Push message takes at most " + MAX_PLAINTEXT + " bytes");
        }
        final byte[] uaPublic = P256.decode(p256dh);
        final byte[] authSecret = P256.decode(auth);
        if (authSecret.length != AUTH_LENGTH) {
            throw new GeneralSecurityException("The authentication secret of a browser has 16 bytes");
        }
        final ECPublicKey browserKey = P256.publicKey(uaPublic);

        final KeyPair sender = keyPairs.get();
        final byte[] asPublic = P256.point((ECPublicKey) sender.getPublic());
        final KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(sender.getPrivate());
        agreement.doPhase(browserKey, true);
        final byte[] ecdhSecret = agreement.generateSecret();

        //RFC 8291, section 3.4: the key of the browser and its secret into the input keying material
        final byte[] prkKey = hmac(authSecret, ecdhSecret);
        final byte[] ikm = hmac(prkKey, concat(KEY_INFO, uaPublic, asPublic, new byte[]{1}));

        //RFC 8188, section 2.2: the content encryption key and the nonce from the salt
        final byte[] salt = salts.get();
        final byte[] prk = hmac(salt, ikm);
        final byte[] cek = Arrays.copyOf(hmac(prk, concat(CEK_INFO, new byte[]{1})), 16);
        final byte[] nonce = Arrays.copyOf(hmac(prk, concat(NONCE_INFO, new byte[]{1})), 12);

        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(TAG_LENGTH * 8, nonce));
        final byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[]{LAST_RECORD}));

        //the header of RFC 8188: salt, record size, the length of the key id and the key id - here the
        //public key of the sender, from which the browser derives the same secret
        final ByteBuffer header = ByteBuffer.allocate(SALT_LENGTH + 4 + 1 + asPublic.length)
            .put(salt)
            .putInt(RECORD_SIZE)
            .put((byte) asPublic.length)
            .put(asPublic);
        return concat(header.array(), ciphertext);
    }

    private static byte[] hmac(final byte[] key, final byte[] data) throws GeneralSecurityException {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(final byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static KeyPair newKeyPair() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(P256.parameters());
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The JDK cannot make a P-256 key pair", e);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private static byte[] newSalt() {
        final byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return salt;
    }
}
