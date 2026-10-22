package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.security.KeyFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;

import org.junit.Test;

public class AdbClientTest {
    // A throwaway 2048-bit modulus and the key structure that the Python adb library (adb_shell.auth.keygen.encode_pubkey)
    // produced for it. Public values only.
    private static final String MODULUS_HEX = "e50392f426a2855fb9127f49b3e51dfdb1c89b7bff2d5f44e05f1ab484ffcb0790e464aa4a52881dd807ea2cc6c591a3ebfe99b762e84cabff2ab96ab8e5270126af0e606d6629437bd95fcde1f453525000b804ecb41de34e99ef9fa1f4fd28e2524bfedbb75c502eae2b1f2b7939248e5c867c5918cf57cb822c0b8c3a607217491106cf3ae83139d8861f521678a631a940b3477e59eed19035d00bbc740638ec394c7b1e5c828d903906cb9195e37defe46f9f72d560b9f9570156f9b982382a86aef7cb36e5ea3c0e3e252521a475510a770b3dae59e8eacb9580054605487324108030cf24bbc8dbbb06b39dade8e5265d5b0cf58f52a2d5e301e56749";
    private static final String EXPECTED_STRUCTURE = "QAAAAAfFR51JZ+UB49WiUo/1DFtdJuXorZ2zBrvbyLskzzCAECRzSAVGBYCVy+roWa49C3cKUXWkISUlPg486uU2y/euhio4grn5VgFX+blg1XKfb+TvfeOVkcsGOZCNglwee0w57DgGdLwL0DWQ0e5ZfkezQKkxpngWUh+G2Dkx6DrPBhFJF3JgOowLLILLV88YWXyGXI4kOXkrHyuuLlBct9v+S1LiKP30oZ/vmU7jHbTsBLgAUFJT9OHNX9l7QylmbWAOryYBJ+W4arkq/6tM6GK3mf7ro5HFxizqB9gdiFJKqmTkkAfL/4S0Gl/gRF8t/3ubyLH9HeWzSX8SuV+Foib0kgPls3vPI4HD0oIzOEGUxaMmYynSxH6jbwOgSZ7cpdzIHe4o2yqq6/cwV+ke1zKfO2w1EAguokp8quid9mlDSG1pzrYPAbfnbkJ0N0bueXy9i8nOTuzMbh96v+HEvGuVpmLE0e/8YVYAEcXVvPfpRkl8a8ZeXIDjd+5lNoc0dR0Q2Z8+Ww228mixcVAyTgJeWJr07NKC6kLLAcIBP2xbp+/cOYO532BNf/x/xAzcw6ENmvMrxj+Cpu6x2BsYoMFv8EsAta/BdIcC2lkljaPm0C9F+nB/+p7mxM7laZIZI8Esue4xLFmUhnfLj9NqKNNPHrMjsxUjK5RY7NOOpBR7o8yVeQEAAQA=";

    private static RSAPublicKey publicKey(String modulusHex) throws Exception {
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(new BigInteger(modulusHex, 16), BigInteger.valueOf(65537)));
    }

    @Test
    public void publicKeyStructureMatchesAdbsOwn() throws Exception {
        byte[] ours = AdbClient.publicKeyStructure(publicKey(MODULUS_HEX));
        assertEquals(524, ours.length);
        assertArrayEquals(Base64.getDecoder().decode(EXPECTED_STRUCTURE), ours);
    }

    @Test
    public void authPublicKeyIsBase64NameAndNul() throws Exception {
        String s = new String(AdbClient.authPublicKey(publicKey(MODULUS_HEX), "tvhome@launcher"), StandardCharsets.UTF_8);
        assertEquals(EXPECTED_STRUCTURE + " tvhome@launcher\0", s);
    }

    @Test
    public void tokenSignatureIsPkcs1OverSha1DigestInfo() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair keys = g.generateKeyPair();
        byte[] token = new byte[20];
        for (int i = 0; i < 20; i++) token[i] = (byte) (i * 7 + 3);
        byte[] sig = AdbClient.sign(keys, token);
        assertEquals(256, sig.length);
        // Undo the signature with the public key: it must be PKCS#1 v1.5 type-1 padding around the SHA-1 DigestInfo and the token.
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.DECRYPT_MODE, keys.getPublic());
        byte[] em = c.doFinal(sig);
        byte[] prefix = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        byte[] tail = new byte[prefix.length + token.length];
        System.arraycopy(prefix, 0, tail, 0, prefix.length);
        System.arraycopy(token, 0, tail, prefix.length, token.length);
        assertEquals(0x00, em[0]);
        assertEquals(0x01, em[1]);
        int sep = tail.length + 1;
        for (int i = 2; i < em.length - sep; i++) assertEquals((byte) 0xff, em[i]);
        assertEquals(0x00, em[em.length - sep]);
        assertArrayEquals(tail, Arrays.copyOfRange(em, em.length - tail.length, em.length));
    }

    @Test
    public void onlyWellFormedPackageNamesAreAccepted() {
        assertTrue(AdbClient.validPackage("com.google.android.youtube.tv"));
        assertTrue(AdbClient.validPackage("com.netflix.ninja"));
        assertTrue(AdbClient.validPackage("tv.pluto.android"));
        assertFalse(AdbClient.validPackage(null));
        assertFalse(AdbClient.validPackage(""));
        assertFalse(AdbClient.validPackage("singleword"));
        assertFalse(AdbClient.validPackage("com.example.app; reboot"));
        assertFalse(AdbClient.validPackage("com.example.app && rm -rf /"));
        assertFalse(AdbClient.validPackage("com.example.$(id)"));
        assertFalse(AdbClient.validPackage("com.example.app\nreboot"));
        assertFalse(AdbClient.validPackage("com.example.app "));
        assertFalse(AdbClient.validPackage("com..example"));
        assertFalse(AdbClient.validPackage(".com.example"));
        assertFalse(AdbClient.validPackage("1com.example"));
    }

    @Test
    public void theCommandIsFixedAndBuiltFromValidatedNamesOnly() {
        assertEquals("am force-stop com.a.b", AdbClient.forceStopCommand(Arrays.asList("com.a.b")));
        assertEquals("am force-stop com.a.b; am force-stop com.c.d", AdbClient.forceStopCommand(Arrays.asList("com.a.b", "com.c.d")));
        for (String bad : new String[] {"com.a.b; reboot", "com.a.b\nreboot", "x", "", "com.a.b|id"}) {
            try {
                AdbClient.forceStopCommand(Arrays.asList(bad));
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }

    @Test
    public void systemPackagesAreRefused() {
        for (String p : new String[] {"android", "com.android.systemui", "com.android.settings", "com.google.android.gms",
                "com.android.providers.media", "com.android.server.telecom"}) {
            assertTrue(p, AdbClient.refused(p));
            try {
                AdbClient.forceStopCommand(Arrays.asList(p));
                fail("accepted " + p);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
        assertFalse(AdbClient.refused("com.google.android.youtube.tv"));
        assertFalse(AdbClient.refused("com.android.vending"));
    }

    @Test
    public void tooManyOrNoPackagesAreRefused() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= AdbClient.MAX_PACKAGES; i++) many.add("com.example.app" + i);
        for (List<String> bad : Arrays.asList(many, new ArrayList<String>())) {
            try {
                AdbClient.forceStopCommand(bad);
                fail("accepted " + bad.size());
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }
}
