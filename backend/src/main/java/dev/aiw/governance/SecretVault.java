package dev.aiw.governance;

import dev.aiw.shared.ApiException;
import dev.aiw.shared.Ids;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores secrets encrypted with AES-256-GCM under the master key (AIW_MASTER_KEY).
 * The secret id is bound as associated data, so a ciphertext cannot be swapped to another row.
 * Plaintext never leaves this class except through {@link #reveal}, which only server-side
 * integrations call; the API never returns secret values.
 */
@Service
public class SecretVault {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final JdbcClient jdbc;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretVault(JdbcClient jdbc, @Value("${aiw.security.master-key}") String masterKeyBase64) {
        this.jdbc = jdbc;
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(masterKeyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("aiw.security.master-key must be base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("aiw.security.master-key must decode to 32 bytes, got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String store(String workspaceId, String projectId, String name, String plaintext) {
        String id = Ids.newId();
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, nonce, id, plaintext.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("INSERT INTO secrets (id, workspace_id, project_id, name, nonce, ciphertext) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, workspaceId, projectId, name, nonce, ciphertext)
                .update();
        return id;
    }

    public String reveal(String workspaceId, String secretId) {
        var row = jdbc.sql("SELECT nonce, ciphertext FROM secrets WHERE id = ? AND workspace_id = ?")
                .params(secretId, workspaceId)
                .query((rs, n) -> new byte[][] {rs.getBytes("nonce"), rs.getBytes("ciphertext")})
                .optional()
                .orElseThrow(() -> ApiException.notFound("Secret"));
        return new String(crypt(Cipher.DECRYPT_MODE, row[0], secretId, row[1]), StandardCharsets.UTF_8);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(String workspaceId, String secretId) {
        jdbc.sql("DELETE FROM secrets WHERE id = ? AND workspace_id = ?").params(secretId, workspaceId).update();
    }

    private byte[] crypt(int mode, byte[] nonce, String aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret encryption failed", e);
        }
    }
}
