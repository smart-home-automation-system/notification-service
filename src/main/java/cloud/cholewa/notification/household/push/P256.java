package cloud.cholewa.notification.household.push;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * The keys of Web Push in the form the browsers and the VAPID tools give them - an uncompressed P-256
 * point (65 bytes: 0x04, x, y) and a 32-byte scalar, base64url - turned into keys of the JDK and back.
 * The JDK does all the cryptography; this is only the encoding no JDK class reads.
 */
final class P256 {

    static final int POINT_LENGTH = 65;
    private static final int COORDINATE_LENGTH = 32;
    private static final byte UNCOMPRESSED = 0x04;

    private static final ECParameterSpec PARAMETERS = loadParameters();

    private P256() {
    }

    static ECParameterSpec parameters() {
        return PARAMETERS;
    }

    private static ECParameterSpec loadParameters() {
        try {
            final AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The JDK has no P-256", e);
        }
    }

    //the length and the form only: whether the point is on the curve is checked by the JDK when the key is
    //used - in the ECDH of the encryptor, and in the signature check of VapidPair at the start
    static ECPublicKey publicKey(final byte[] point) throws GeneralSecurityException {
        if (point.length != POINT_LENGTH || point[0] != UNCOMPRESSED) {
            throw new GeneralSecurityException("Not an uncompressed P-256 point of 65 bytes");
        }
        final BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 1 + COORDINATE_LENGTH));
        final BigInteger y = new BigInteger(1, Arrays.copyOfRange(point, 1 + COORDINATE_LENGTH, POINT_LENGTH));
        return (ECPublicKey) KeyFactory.getInstance("EC")
            .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), parameters()));
    }

    static ECPrivateKey privateKey(final byte[] scalar) throws GeneralSecurityException {
        if (scalar.length != COORDINATE_LENGTH) {
            throw new GeneralSecurityException("Not a P-256 private key of 32 bytes");
        }
        return (ECPrivateKey) KeyFactory.getInstance("EC")
            .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), parameters()));
    }

    static byte[] point(final ECPublicKey key) {
        final byte[] point = new byte[POINT_LENGTH];
        point[0] = UNCOMPRESSED;
        copyUnsigned(key.getW().getAffineX(), point, 1);
        copyUnsigned(key.getW().getAffineY(), point, 1 + COORDINATE_LENGTH);
        return point;
    }

    static byte[] decode(final String base64url) {
        //a browser may hand the key with its padding, the tools without
        return Base64.getUrlDecoder().decode(base64url.strip().replace("=", ""));
    }

    static String encode(final byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    //a coordinate as exactly 32 bytes: BigInteger drops leading zeros and may add a sign byte
    private static void copyUnsigned(final BigInteger value, final byte[] target, final int offset) {
        final byte[] bytes = value.toByteArray();
        final int length = Math.min(bytes.length, COORDINATE_LENGTH);
        System.arraycopy(bytes, bytes.length - length, target, offset + COORDINATE_LENGTH - length, length);
    }
}
