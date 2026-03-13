package chat.fold.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "fold.media-processing")
public interface FoldMediaProcessingConfig {

    /** Max video duration in seconds */
    @WithDefault("300")
    int maxVideoDuration();

    /** Max video upload size in bytes (100MB) */
    @WithDefault("104857600")
    long maxVideoSize();

    /** Max image upload size in bytes (20MB) */
    @WithDefault("20971520")
    long maxImageSize();

    /** Thumbnail max width in pixels */
    @WithDefault("400")
    int thumbnailMaxWidth();

    /** Path to ffmpeg binary */
    @WithDefault("ffmpeg")
    String ffmpegPath();

    /** Path to ffprobe binary */
    @WithDefault("ffprobe")
    String ffprobePath();

    /** Video mode: disabled | no-transcode | transcode */
    @WithDefault("no-transcode")
    String videoMode();

    /** HW acceleration: auto | none | videotoolbox | nvenc | vaapi | qsv */
    @WithDefault("auto")
    String hwAccel();

    /** Max concurrent ffmpeg jobs */
    @WithDefault("2")
    int maxConcurrentJobs();
}
