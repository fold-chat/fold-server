package chat.kith.service;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Generates deterministic default avatars based on username.
 * Produces an SVG circle with a color derived from the username hash
 * and the first letter of the username.
 */
@ApplicationScoped
public class AvatarService {

    // Curated palette — visually distinct, pleasant colors
    private static final String[] COLORS = {
            "#E45B5B", "#E4845B", "#E4B85B", "#A3C940",
            "#4BC9A0", "#4BA3C9", "#5B7BE4", "#8B5BE4",
            "#C95BB5", "#C9404D", "#2D9CDB", "#27AE60",
            "#F2994A", "#9B51E0", "#EB5757", "#56CCF2"
    };

    /**
     * Generate an SVG default avatar for the given username.
     * The color is deterministic — same username always produces the same avatar.
     */
    public String generateSvg(String username) {
        if (username == null || username.isBlank()) {
            username = "?";
        }

        String color = colorForUsername(username);
        String letter = username.substring(0, 1).toUpperCase();

        return """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128" width="128" height="128">
                  <circle cx="64" cy="64" r="64" fill="%s"/>
                  <text x="64" y="64" dy="0.35em" text-anchor="middle"
                        font-family="-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif"
                        font-size="56" font-weight="600" fill="white">%s</text>
                </svg>
                """.formatted(color, letter);
    }

    /**
     * Returns the default avatar URL path for a username.
     */
    public String defaultAvatarUrl(String username) {
        return "/api/v0/avatars/default/" + username;
    }

    private String colorForUsername(String username) {
        // Simple deterministic hash to pick a color
        int hash = 0;
        for (int i = 0; i < username.length(); i++) {
            hash = 31 * hash + username.charAt(i);
        }
        int index = Math.abs(hash) % COLORS.length;
        return COLORS[index];
    }
}
