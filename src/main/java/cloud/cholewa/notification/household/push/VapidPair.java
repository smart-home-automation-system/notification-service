package cloud.cholewa.notification.household.push;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;

/**
 * Whether the two VAPID keys of the configuration are one pair - checked at the start (HAS-276). They
 * live apart (the private key in the secret, the public one twice in the manifests), and a public key
 * of another pair is what the browsers subscribe with: every push would then be refused with 403, and
 * only the log would say so. The JDK computes no public key from a private one, so a signature of the
 * private key is checked with the public one instead.
 */
final class VapidPair {

    private static final byte[] PROBE = "household.push".getBytes(StandardCharsets.US_ASCII);

    private VapidPair() {
    }

    static boolean matches(final ECPrivateKey privateKey, final byte[] publicKey) throws GeneralSecurityException {
        final Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(PROBE);
        final byte[] signature = signer.sign();

        final Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(P256.publicKey(publicKey));
        verifier.update(PROBE);
        return verifier.verify(signature);
    }
}
