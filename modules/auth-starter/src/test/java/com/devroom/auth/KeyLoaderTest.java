package com.devroom.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KeyLoaderTest {

    @Test
    void loadsPrivateKeyFromPemFile(@TempDir Path tempDir) throws Exception {
        KeyPair pair = generateKeyPair();
        Path privateKeyPath = tempDir.resolve("private.pem");
        Files.writeString(privateKeyPath, toPem("PRIVATE KEY", pair.getPrivate().getEncoded()));

        PrivateKey loaded = KeyLoader.loadPrivateKey(privateKeyPath);

        assertNotNull(loaded);
        assertEquals("RSA", loaded.getAlgorithm());
    }

    @Test
    void loadsPublicKeyFromPemFile(@TempDir Path tempDir) throws Exception {
        KeyPair pair = generateKeyPair();
        Path publicKeyPath = tempDir.resolve("public.pem");
        Files.writeString(publicKeyPath, toPem("PUBLIC KEY", pair.getPublic().getEncoded()));

        PublicKey loaded = KeyLoader.loadPublicKey(publicKeyPath);

        assertNotNull(loaded);
        assertEquals("RSA", loaded.getAlgorithm());
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static String toPem(String type, byte[] keyBytes) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyBytes);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }
}
