package chat.fray.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "fray.file")
public interface FrayFileConfig {
    @WithDefault("./data")
    String dataDir();

    /** Max upload size in bytes */
    @WithDefault("10485760")
    long maxUploadSize();
}
