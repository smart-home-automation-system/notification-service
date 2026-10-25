package cloud.cholewa.notification.household.push;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class VapidSignerTest {

    private static final Instant NOW = Instant.parse("2026-10-25T16:00:00Z");

    //RFC 8292: the token is signed by the private key, checked with the public key the browser subscribed with
    @Test
    void should_sign_a_token_for_the_origin_of_the_push_service_that_its_public_key_verifies() throws GeneralSecurityException {
        final KeyPair pair = pair();
        final String publicKey = P256.encode(P256.point((ECPublicKey) pair.getPublic()));
        final VapidSigner sut = new VapidSigner((ECPrivateKey) pair.getPrivate(), publicKey, "mailto:owner@example.com",
            Clock.fixed(NOW, ZoneOffset.UTC));

        final String authorization = sut.authorization(URI.create("https://web.push.apple.com:443/QGuQyavXutnMH?x=1"));

        assertThat(authorization).startsWith("vapid t=").endsWith(", k=" + publicKey);
        final String token = authorization.substring("vapid t=".length(), authorization.indexOf(", k="));
        final String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(new String(P256.decode(parts[0]), StandardCharsets.UTF_8)).isEqualTo("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
        assertThat(new String(P256.decode(parts[1]), StandardCharsets.UTF_8)).isEqualTo(
            "{\"aud\":\"https://web.push.apple.com\",\"exp\":" + NOW.plus(VapidSigner.LIFETIME).getEpochSecond()
                + ",\"sub\":\"mailto:owner@example.com\"}");

        final byte[] signature = P256.decode(parts[2]);
        assertThat(signature).hasSize(64);
        final Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(signature)).isTrue();
    }

    //the origin of the push service, as it compares it: no default port, no user info
    @Test
    void should_name_the_origin_without_the_default_port() throws GeneralSecurityException {
        final KeyPair pair = pair();
        final VapidSigner sut = new VapidSigner((ECPrivateKey) pair.getPrivate(), "k", "mailto:owner@example.com",
            Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(audienceOf(sut.authorization(URI.create("https://user@web.push.apple.com:443/x"))))
            .contains("\"aud\":\"https://web.push.apple.com\"");
        assertThat(audienceOf(sut.authorization(URI.create("https://push.example.com:8443/x"))))
            .contains("\"aud\":\"https://push.example.com:8443\"");
    }

    private static String audienceOf(final String authorization) {
        final String token = authorization.substring("vapid t=".length(), authorization.indexOf(", k="));
        return new String(P256.decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    @Test
    void should_tell_a_pair_from_two_keys_of_different_pairs() throws GeneralSecurityException {
        final KeyPair one = pair();
        final KeyPair other = pair();

        assertThat(VapidPair.matches((ECPrivateKey) one.getPrivate(), P256.point((ECPublicKey) one.getPublic()))).isTrue();
        assertThat(VapidPair.matches((ECPrivateKey) one.getPrivate(), P256.point((ECPublicKey) other.getPublic()))).isFalse();
    }

    static KeyPair pair() throws GeneralSecurityException {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(P256.parameters());
        return generator.generateKeyPair();
    }
}
