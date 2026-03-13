package chat.fold.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "fold.file")
public interface FoldFileConfig {
    @WithDefault("./data")
    String dataDir();

    /** Max upload size in bytes */
    @WithDefault("10485760")
    long maxUploadSize();
}
