package cloud.cholewa.notification.household.push;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//The keys and the subject are checked at the start (HAS-276): a pair that is not one would fail every push
class WebPushConfigTest {

    private static final List<String> HOSTS = List.of("push.apple.com");

    @Test
    void should_take_a_vapid_pair_and_a_mailto_subject() throws GeneralSecurityException {
        final KeyPair pair = VapidSignerTest.pair();
        final WebPushProperties properties = properties(privateKeyOf(pair), publicKeyOf(pair), "mailto:owner@example.com");

        assertThat(WebPushConfig.checkedPrivateKey(properties)).isNotNull();
        assertThat(WebPushConfig.checkedSubject(properties)).isEqualTo("mailto:owner@example.com");
    }

    //the browsers subscribed with the key unpadded: a key written with its padding is sent without it
    @Test
    void should_send_the_public_key_in_the_form_the_browsers_subscribed_with() throws GeneralSecurityException {
        final KeyPair pair = VapidSignerTest.pair();
        final WebPushProperties properties = properties(privateKeyOf(pair), publicKeyOf(pair) + "= ", "mailto:owner@example.com");

        final String authorization = new WebPushConfig().vapidSigner(properties)
            .authorization(java.net.URI.create("https://web.push.apple.com/x"));

        assertThat(authorization).endsWith(", k=" + publicKeyOf(pair));
    }

    @Test
    void should_refuse_a_public_key_of_another_pair() throws GeneralSecurityException {
        final WebPushProperties properties = properties(
            privateKeyOf(VapidSignerTest.pair()), publicKeyOf(VapidSignerTest.pair()), "mailto:owner@example.com");

        assertThatThrownBy(() -> WebPushConfig.checkedPrivateKey(properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("household.push.public-key is not the public key of household.push.private-key");
    }

    @Test
    void should_refuse_missing_or_unreadable_keys() throws GeneralSecurityException {
        final KeyPair pair = VapidSignerTest.pair();

        assertThatThrownBy(() -> WebPushConfig.checkedPrivateKey(properties("", publicKeyOf(pair), "mailto:a@b.c")))
            .hasMessageContaining("are required while household.push.enabled is on");
        assertThatThrownBy(() -> WebPushConfig.checkedPrivateKey(properties("c2hvcnQ", publicKeyOf(pair), "mailto:a@b.c")))
            .hasMessage("The VAPID keys of household.push cannot be read: Not a P-256 private key of 32 bytes");
        assertThatThrownBy(() -> WebPushConfig.checkedPrivateKey(properties(privateKeyOf(pair), "not*base64", "mailto:a@b.c")))
            .hasMessageStartingWith("The VAPID keys of household.push cannot be read");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "owner@example.com", "mailto:", "http://example.com", "mailto:owner", "mailto:a\"b@example.com", "https://example.com/a\\b"})
    void should_refuse_a_subject_that_is_neither_a_mailto_address_nor_an_https_url(final String subject) {
        assertThatThrownBy(() -> WebPushConfig.checkedSubject(properties("x", "y", subject)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("household.push.subject must be a mailto: address or an https: URL while household.push.enabled is on");
    }

    private static WebPushProperties properties(final String privateKey, final String publicKey, final String subject) {
        return new WebPushProperties(true, subject, publicKey, privateKey, Duration.ofHours(1), HOSTS);
    }

    static String privateKeyOf(final KeyPair pair) {
        final byte[] scalar = ((ECPrivateKey) pair.getPrivate()).getS().toByteArray();
        final byte[] fixed = new byte[32];
        final int length = Math.min(scalar.length, 32);
        System.arraycopy(scalar, scalar.length - length, fixed, 32 - length, length);
        return P256.encode(fixed);
    }

    static String publicKeyOf(final KeyPair pair) {
        return P256.encode(P256.point((ECPublicKey) pair.getPublic()));
    }
}
