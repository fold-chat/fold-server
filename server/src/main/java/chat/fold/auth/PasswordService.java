package chat.fold.auth;

import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.security.SecureRandom;
import java.util.Base64;

@ApplicationScoped
public class PasswordService {

    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int ITERATIONS = 3;
    private static final int MEMORY_KB = 65536; // 64MB
    private static final int PARALLELISM = 1;

    private final SecureRandom random = new SecureRandom();

    /**
     * Hash password with argon2id. Returns encoded string: $argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>
     */
    public String hash(String password) {
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);

        var params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withIterations(ITERATIONS)
                .withMemoryAsKB(MEMORY_KB)
                .withParallelism(PARALLELISM)
                .build();

        byte[] hash = new byte[HASH_LENGTH];
        var generator = new Argon2BytesGenerator();
        generator.init(params);
        generator.generateBytes(password.toCharArray(), hash);

        String b64Salt = Base64.getEncoder().withoutPadding().encodeToString(salt);
        String b64Hash = Base64.getEncoder().withoutPadding().encodeToString(hash);
        return "$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s".formatted(MEMORY_KB, ITERATIONS, PARALLELISM, b64Salt, b64Hash);
    }

    /**
     * Verify password against encoded hash string.
     */
    public boolean verify(String password, String encodedHash) {
        try {
            // Parse: $argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>
            String[] parts = encodedHash.split("\\$");
            // parts[0]="" parts[1]="argon2id" parts[2]="v=19" parts[3]="m=...,t=...,p=..." parts[4]=salt parts[5]=hash
            if (parts.length != 6) return false;

            String[] paramParts = parts[3].split(",");
            int memory = Integer.parseInt(paramParts[0].substring(2));
            int iterations = Integer.parseInt(paramParts[1].substring(2));
            int parallelism = Integer.parseInt(paramParts[2].substring(2));
            byte[] salt = Base64.getDecoder().decode(parts[4]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[5]);

            var params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withSalt(salt)
                    .withIterations(iterations)
                    .withMemoryAsKB(memory)
                    .withParallelism(parallelism)
                    .build();

            byte[] actualHash = new byte[expectedHash.length];
            var generator = new Argon2BytesGenerator();
            generator.init(params);
            generator.generateBytes(password.toCharArray(), actualHash);

            return constantTimeEquals(expectedHash, actualHash);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
