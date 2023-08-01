package migt;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.SignedJWT;
import org.json.JSONException;

import java.text.ParseException;
import java.util.Base64;


/**
 * Class to manage JWT tokens
 * Uses https://connect2id.com/products/nimbus-jose-jwt
 * {@code @Author} Matteo Bitussi
 */
public class JWT {
    public String header;
    public String payload;
    public String signature;
    public String raw;
    SignedJWT parsed_jwt;

    public boolean sign; // put to true if you want to sign the jwt after edit (need private key)
    public String private_key_pem; // PEM the private key used to sign the jwt
    public String public_key_pem; // PEM public key used to check the signature of the jwt

    public boolean decrypt; // put to true if the raw is a JWE, and you want to decrypt it
    public String private_key_pem_enc;
    public String public_key_pem_enc;
    public JWEObject jwe;
    EncryptingAlg e_alg;
    SigningAlgs signing_alg;

    /**
     * Constructor that instantiate a JWT object
     */
    public JWT() {
        raw = "";
        signature = "";
        header = "";
        payload = "";
        sign = false;
        private_key_pem = "";
        public_key_pem = "";
        private_key_pem_enc = "";
        public_key_pem_enc = "";
        decrypt = false;
        jwe = null;
        e_alg = null;
    }

    /**
     * Parse a JWT token from a string and stores header, payload, and signature string values inside the JWT object
     *
     * @param raw_jwt the raw JWT in string format
     * @throws ParsingException if there are problems in the parsing
     */
    public void parse(String raw_jwt) throws ParsingException {
        this.raw = raw_jwt;

        if (decrypt) {
            // it is a JWE containing a JWT
            try {
                jwe = JWEObject.parse(raw_jwt);
                e_alg = EncryptingAlg.fromString(jwe.getHeader().getAlgorithm().getName());
                JWK jwk_private_enc = JWK.parseFromPEMEncodedObjects(private_key_pem_enc);

                switch (e_alg) {
                    case RSA_OAEP:
                    case RSA_OAEP_256:
                        jwe.decrypt(new RSADecrypter(jwk_private_enc.toRSAKey()));
                        break;
                    case ECDH_ES_A128KW:
                    case ECDH_ES_A256KW:
                        jwe.decrypt(new ECDHDecrypter(jwk_private_enc.toECKey()));
                        break;
                }

                parsed_jwt = jwe.getPayload().toSignedJWT();
                if (parsed_jwt == null) {
                    throw new ParsingException("Error, JWE payload is not a JWS");
                }
            } catch (ParseException | JOSEException e) {
                throw new ParsingException("problem in decrypting JWE: " + e);
            }
        }

        try {
            if (!decrypt) // otherwise it is already parsed
                parsed_jwt = SignedJWT.parse(raw_jwt);
            JWSHeader header = parsed_jwt.getHeader();
            signing_alg = SigningAlgs.fromString(header.getAlgorithm().getName());
        } catch (ParseException e) {
            throw new ParsingException("Error while parsing jwt: " + e);
        }

        try {
            header = parsed_jwt.getHeader().toString();
            payload = parsed_jwt.getPayload().toString();
            signature = parsed_jwt.getSignature().toString();
        } catch (JSONException e) {
            throw new ParsingException("Error parsing JWT tokens");
        }
    }

    /**
     * Check the signature of the jws using the public_key_pem
     *
     * @return true if the jwt signature is valid, false otherwise
     * @throws ParsingException if something goes wrong while checking signature of the jwt
     */
    public boolean check_sig() throws ParsingException {
        boolean res = false;
        if (parsed_jwt == null) {
            throw new RuntimeException("JWT need to be parsed before checking signature");
        }

        JWK pub_key_jwk = null;
        try {
            pub_key_jwk = JWK.parseFromPEMEncodedObjects(public_key_pem);
        } catch (JOSEException e) {
            throw new ParsingException("Problem in loading public key, " + e);
        }
        switch (signing_alg) {
            case RS256:
            case RS512:
                JWSVerifier verifier = null;
                try {
                    verifier = new RSASSAVerifier(pub_key_jwk.toRSAKey());
                } catch (JOSEException e) {
                    throw new ParsingException("Invalid public key used do verify jwt. " + e);
                }
                try {
                    res = parsed_jwt.verify(verifier);
                } catch (JOSEException e) {
                    throw new ParsingException("The jws could not be verified. " + e);
                }
                break;
        }
        return res;
    }

    /**
     * Builds a JWT token from the string values in this class
     *
     * @return A JWT as a string
     * @throws ParsingException if there are problems building the jwt
     */
    public String build() throws ParsingException {
        String res = "";

        if (this.parsed_jwt == null) {
            throw new ParsingException("error in building jwt, no jwt have been parsed");
        }

        if (this.header.equals("") || this.payload.equals(""))
            throw new ParsingException("error in building jwt, empty values");

        res += Base64.getEncoder().encodeToString(header.getBytes());
        res += "." + Base64.getEncoder().encodeToString(payload.getBytes());

        if (sign) {
            // sign the jwt with sk
            JWSObject signed_jws = null;
            JWK private_key_jwk = null;
            try {
                JWSHeader header_j = JWSHeader.parse(header);
                Payload payload_j = new Payload(payload);
                signed_jws = new JWSObject(header_j, payload_j);
                private_key_jwk = JWK.parseFromPEMEncodedObjects(private_key_pem);
            } catch (ParseException e) {
                throw new ParsingException("unable to build jwt: " + e);
            } catch (JOSEException e) {
                throw new ParsingException("Unable to load public key to sign jwt" + e);
            }
            JWSSigner signer = null;

            try {
                switch (signing_alg) {
                    case RS256:
                    case RS512:
                        signer = new RSASSASigner(private_key_jwk.toRSAKey());
                        break;
                    default:
                        throw new ParsingException("unsupported signing algorithm" + signing_alg);
                }
            } catch (JOSEException e) {
                throw new ParsingException("unable to use public key to sign jwt: " + e);
            } catch (IllegalArgumentException e) {
                throw new ParsingException("invalid private key: " + e);
            }

            try {
                signed_jws.sign(signer);
            } catch (JOSEException e) {
                throw new ParsingException("unable to sign jwt: " + e);
            }

            res = signed_jws.serialize();
        } else {
            if (signature != null && !signature.equals("")) {
                res += "." + signature;
            }
        }

        if (decrypt) {
            // if the JWE has been decrypted, now it needs to be re-encrypted
            if (public_key_pem_enc.length() == 0)
                throw new ParsingException("Cannot enctypt jwe, public key not set");

            JWEObject editedJWE = new JWEObject(
                    jwe.getHeader(),
                    new Payload(res)
            );
            try {
                switch (e_alg) {
                    case RSA_OAEP:
                    case RSA_OAEP_256:
                        editedJWE.encrypt(
                                new RSAEncrypter(JWK.parseFromPEMEncodedObjects(public_key_pem_enc).toRSAKey()));
                        break;
                    case ECDH_ES_A128KW:
                    case ECDH_ES_A256KW:
                        editedJWE.encrypt(
                                new ECDHEncrypter(JWK.parseFromPEMEncodedObjects(public_key_pem_enc).toECKey()));
                        break;
                }
            } catch (JOSEException e) {
                throw new ParsingException("Unable to encrypt JWE " + e);
            }
            res = editedJWE.serialize();
        }

        res = res.replaceAll("=", "");
        return res;
    }

    /**
     * All JWS signing algs supported
     */
    public enum SigningAlgs {
        RS256,
        RS512;

        public static SigningAlgs fromString(String algStr) throws ParsingException {
            switch (algStr) {
                case "RS256":
                    return RS256;
                case "RS512":
                    return RS512;
                default:
                    throw new ParsingException("Unsupported signing algorithm \"" + algStr + "\"");
            }
        }
    }

    /**
     * All JWE encrypting algs supported
     */
    public enum EncryptingAlg {
        RSA_OAEP,
        RSA_OAEP_256,
        ECDH_ES_A128KW,
        ECDH_ES_A256KW;

        public static EncryptingAlg fromString(String algStr) throws ParsingException {
            switch (algStr) {
                case "RSA-OAEP":
                    return RSA_OAEP;
                case "RSA-OAEP-256":
                    return RSA_OAEP_256;
                case "ECDH-ES+A128KW":
                    return ECDH_ES_A128KW;
                case "ECDH-ES+A256KW":
                    return ECDH_ES_A256KW;
                default:
                    throw new ParsingException("Encrypting algorithm " + algStr + " not supported");
            }
        }
    }
}
