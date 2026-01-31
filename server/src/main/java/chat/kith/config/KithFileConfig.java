package chat.kith.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "kith.file")
public interface KithFileConfig {
    @WithDefault("./data")
    String dataDir();

    /** Max upload size in bytes */
    @WithDefault("10485760")
    long maxUploadSize();
}
