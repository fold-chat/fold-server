package chat.fold.cli;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.security.SecureRandom;
import java.util.Base64;

/** Standalone Argon2id hashing for CLI — mirrors PasswordService without CDI. */
public final class AdminPasswordHelper {

    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int ITERATIONS = 3;
    private static final int MEMORY_KB = 65536;
    private static final int PARALLELISM = 1;

    // Excludes ambiguous chars: 0 O l I 1
    private static final String TEMP_PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private AdminPasswordHelper() {}

    public static String hash(String password) {
        var random = new SecureRandom();
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

    public static String generateTempPassword() {
        var random = new SecureRandom();
        var sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(TEMP_PASSWORD_CHARS.charAt(random.nextInt(TEMP_PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
