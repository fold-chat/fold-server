package chat.fray.auth;

import chat.fray.config.FrayAuthConfig;
import chat.fray.db.UserRepository;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.UUID;

@ApplicationScoped
@Startup
public class SetupService {

    private static final Logger LOG = Logger.getLogger(SetupService.class);

    @Inject UserRepository userRepo;
    @Inject PasswordService passwordService;
    @Inject FrayAuthConfig authConfig;

    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev) {
        autoCreateAdmin();
    }

    public boolean isSetupRequired() {
        return userRepo.countUsers() == 0;
    }

    /**
     * Create initial admin user. Only works when 0 users exist.
     * @return userId
     */
    public String createAdmin(String username, String password) {
        if (!isSetupRequired()) {
            throw new AuthService.AuthException("setup_complete", "Setup already completed");
        }

        if (username == null || username.length() < 2 || username.length() > 32) {
            throw new AuthService.AuthException("invalid_username", "Username must be 2-32 characters");
        }
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new AuthService.AuthException("invalid_password", "Password must be 8-128 characters");
        }

        String userId = UUID.randomUUID().toString();
        String hash = passwordService.hash(password);
        userRepo.create(userId, username, username, hash);
        userRepo.assignRole(userId, "owner");

        LOG.infof("Admin user '%s' created", username);
        return userId;
    }

    /** Auto-create admin from env vars if set and no users exist */
    private void autoCreateAdmin() {
        var adminUser = authConfig.adminUsername().filter(s -> !s.isBlank());
        var adminPass = authConfig.adminPassword().filter(s -> !s.isBlank());

        if (adminUser.isPresent() && adminPass.isPresent() && isSetupRequired()) {
            createAdmin(adminUser.get(), adminPass.get());
            LOG.info("[BOOT] Admin auto-created from env vars");
        }
    }
}
