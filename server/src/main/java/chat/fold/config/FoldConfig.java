package chat.fold.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "fold.db")
public interface FoldConfig {
@WithDefault("fold.db")
    String path();

    Optional<String> url();

    Optional<String> authToken();

    @WithDefault("60")
    long syncInterval();

    @WithDefault("4")
    int poolSize();
}
