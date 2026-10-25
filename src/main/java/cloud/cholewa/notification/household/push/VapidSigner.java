package cloud.cholewa.notification.household.push;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Duration;

/**
 * The {@code Authorization} of a Web Push request (RFC 8292): {@code vapid t=<JWT>, k=<public key>}. The
 * token is signed with ES256 by the private key of the household - the push service checks it against
 * the public key the browser subscribed with, so only this sender can post to those subscriptions.
 * <p>
 * The audience is the origin of the push service; the token expires {@link #LIFETIME} after it is made
 * (the RFC allows at most 24 hours). A token is made for every request - a handful a day.
 */
final class VapidSigner {

    static final Duration LIFETIME = Duration.ofHours(12);
    private static final String HEADER = P256.encode("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));

    private final ECPrivateKey privateKey;
    private final String publicKey;
    private final String subject;
    private final Clock clock;

    VapidSigner(final ECPrivateKey privateKey, final String publicKey, final String subject, final Clock clock) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.subject = subject;
        this.clock = clock;
    }

    String authorization(final URI endpoint) throws GeneralSecurityException {
        //the origin of the push service: no user info, and no port where it is the default one of https
        final int port = endpoint.getPort();
        final String audience = endpoint.getScheme() + "://" + endpoint.getHost() + (port == -1 || port == 443 ? "" : ":" + port);
        final long expiry = clock.instant().plus(LIFETIME).getEpochSecond();
        //the three values are ours or an origin of an allowed host - nothing in them needs escaping
        final String claims = P256.encode(("{\"aud\":\"" + audience + "\",\"exp\":" + expiry + ",\"sub\":\"" + subject + "\"}")
            .getBytes(StandardCharsets.UTF_8));
        final String unsigned = HEADER + "." + claims;

        //the JWT takes the raw r || s of 64 bytes, not the DER the plain SHA256withECDSA gives
        final Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(privateKey);
        signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        return "vapid t=" + unsigned + "." + P256.encode(signature.sign()) + ", k=" + publicKey;
    }
}
