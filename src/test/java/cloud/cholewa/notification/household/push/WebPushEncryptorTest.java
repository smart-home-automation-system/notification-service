package cloud.cholewa.notification.household.push;

import org.junit.jupiter.api.Test;

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
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebPushEncryptorTest {

    //RFC 8291, section 5 and appendix A: every input fixed, the result known
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String HEADER = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String CIPHERTEXT = "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ";

    @Test
    void should_encrypt_the_example_of_rfc_8291_to_the_bytes_it_gives() throws GeneralSecurityException {
        final KeyPair sender = new KeyPair(P256.publicKey(P256.decode(AS_PUBLIC)), P256.privateKey(P256.decode(AS_PRIVATE)));
        final WebPushEncryptor sut = new WebPushEncryptor(() -> sender, () -> P256.decode(SALT));

        final byte[] body = sut.encrypt(PLAINTEXT.getBytes(StandardCharsets.UTF_8), UA_PUBLIC, AUTH);

        final ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(P256.decode(HEADER));
        expected.writeBytes(P256.decode(CIPHERTEXT));
        assertThat(body).isEqualTo(expected.toByteArray());
    }

    //what a browser does with it: the key id of the header and its own private key give the same secret
    @Test
    void should_encrypt_what_the_browser_decrypts_with_a_new_key_and_salt_each_time() throws GeneralSecurityException {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(P256.parameters());
        final KeyPair browser = generator.generateKeyPair();
        final String p256dh = P256.encode(P256.point((ECPublicKey) browser.getPublic()));
        final WebPushEncryptor sut = new WebPushEncryptor();
        final byte[] message = "{\"notification\":{\"title\":\"Zmywarka\"}}".getBytes(StandardCharsets.UTF_8);

        final byte[] first = sut.encrypt(message, p256dh, AUTH);
        final byte[] second = sut.encrypt(message, p256dh, AUTH);

        assertThat(decrypt(first, (ECPrivateKey) browser.getPrivate(), P256.decode(p256dh), P256.decode(AUTH))).isEqualTo(message);
        assertThat(decrypt(second, (ECPrivateKey) browser.getPrivate(), P256.decode(p256dh), P256.decode(AUTH))).isEqualTo(message);
        assertThat(Arrays.copyOf(first, 16)).isNotEqualTo(Arrays.copyOf(second, 16));
        assertThat(Arrays.copyOfRange(first, 21, 86)).isNotEqualTo(Arrays.copyOfRange(second, 21, 86));
    }

    @Test
    void should_refuse_a_message_longer_than_one_record() {
        final WebPushEncryptor sut = new WebPushEncryptor();

        assertThatThrownBy(() -> sut.encrypt(new byte[WebPushEncryptor.MAX_PLAINTEXT + 1], UA_PUBLIC, AUTH))
            .isInstanceOf(GeneralSecurityException.class)
            .hasMessage("A Web Push message takes at most 4079 bytes");
    }

    @Test
    void should_refuse_keys_a_browser_gave_wrong() {
        final WebPushEncryptor sut = new WebPushEncryptor();
        final byte[] message = "x".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> sut.encrypt(message, UA_PUBLIC, "c2hvcnQ"))
            .isInstanceOf(GeneralSecurityException.class)
            .hasMessage("The authentication secret of a browser has 16 bytes");
        assertThatThrownBy(() -> sut.encrypt(message, AUTH, AUTH))
            .isInstanceOf(GeneralSecurityException.class)
            .hasMessage("Not an uncompressed P-256 point of 65 bytes");
    }

    @Test
    void should_read_the_private_key_of_the_example() throws GeneralSecurityException {
        assertThat(P256.privateKey(P256.decode(UA_PRIVATE))).isNotNull();
    }

    //RFC 8291 from the side of the browser, written apart from the encryptor
    private static byte[] decrypt(final byte[] body, final ECPrivateKey uaPrivate, final byte[] uaPublic, final byte[] auth)
        throws GeneralSecurityException {
        final ByteBuffer buffer = ByteBuffer.wrap(body);
        final byte[] salt = new byte[16];
        buffer.get(salt);
        assertThat(buffer.getInt()).isEqualTo(4096);
        final byte[] asPublic = new byte[buffer.get()];
        buffer.get(asPublic);
        final byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        final KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(uaPrivate);
        agreement.doPhase(P256.publicKey(asPublic), true);
        final byte[] ecdh = agreement.generateSecret();
        final byte[] prkKey = hmac(auth, ecdh);
        final ByteArrayOutputStream keyInfo = new ByteArrayOutputStream();
        keyInfo.writeBytes("WebPush: info\0".getBytes(StandardCharsets.US_ASCII));
        keyInfo.writeBytes(uaPublic);
        keyInfo.writeBytes(asPublic);
        keyInfo.write(1);
        final byte[] ikm = hmac(prkKey, keyInfo.toByteArray());
        final byte[] prk = hmac(salt, ikm);
        final byte[] cek = Arrays.copyOf(hmac(prk, "Content-Encoding: aes128gcm\0\1".getBytes(StandardCharsets.US_ASCII)), 16);
        final byte[] nonce = Arrays.copyOf(hmac(prk, "Content-Encoding: nonce\0\1".getBytes(StandardCharsets.US_ASCII)), 12);

        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        final byte[] padded = cipher.doFinal(ciphertext);
        assertThat(padded[padded.length - 1]).isEqualTo((byte) 2);
        return Arrays.copyOf(padded, padded.length - 1);
    }

    private static byte[] hmac(final byte[] key, final byte[] data) throws GeneralSecurityException {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
