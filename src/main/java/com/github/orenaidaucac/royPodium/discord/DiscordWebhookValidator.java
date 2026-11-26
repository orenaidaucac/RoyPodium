package com.github.orenaidaucac.royPodium.discord;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates Discord Webhook URLs (HTTPS, official Discord hosts, anti-SSRF) and masks secrets.
 */
public final class DiscordWebhookValidator {

    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "discord.com",
            "discordapp.com",
            "ptb.discord.com",
            "canary.discord.com"
    );

    private static final Pattern WEBHOOK_PATH = Pattern.compile(
            "^/api/(?:v\\d+/)?webhooks/([0-9]{10,30})/([A-Za-z0-9._-]{10,120})/?$"
    );

    private DiscordWebhookValidator() {
    }

    public static ValidationResult validate(String rawUrl) {
        return validate(rawUrl, true);
    }

    static ValidationResult validate(String rawUrl, boolean resolveDns) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return ValidationResult.fail("Webhook URL is empty.");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            return ValidationResult.fail("Malformed URL syntax.");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return ValidationResult.fail("Webhook URL must use HTTPS.");
        }
        String host = uri.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            return ValidationResult.fail("Host must be an official Discord webhook domain (discord.com / discordapp.com).");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return ValidationResult.fail("Non-standard port is not allowed for Discord webhooks.");
        }
        String path = uri.getPath();
        if (path == null || !WEBHOOK_PATH.matcher(path).matches()) {
            return ValidationResult.fail("URL path must match '/api/webhooks/<id>/<token>'.");
        }

        if (resolveDns) {
            try {
                InetAddress[] addresses = InetAddress.getAllByName(host);
                if (addresses.length == 0) {
                    return ValidationResult.fail("Could not resolve host '" + host + "'.");
                }
                for (InetAddress addr : addresses) {
                    if (isPrivateOrReserved(addr)) {
                        return ValidationResult.fail("Blocked private/internal IP address (SSRF protection).");
                    }
                }
            } catch (Exception ex) {
                return ValidationResult.fail("DNS resolution failed for host '" + host + "': " + ex.getMessage());
            }
        }

        return ValidationResult.ok();
    }

    static boolean isPrivateOrReserved(InetAddress addr) {
        if (addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = addr.getAddress();
        // IPv6 Unique Local Address (fc00::/7)
        if (bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
            return true;
        }
        // IPv4 CGNAT 100.64.0.0/10
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Masks a webhook URL so its secret token and full ID are never exposed in logs, chat, or GUI.
     */
    public static String maskUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "<unset>";
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String path = uri.getPath();
            if (path != null) {
                Matcher m = WEBHOOK_PATH.matcher(path);
                if (m.matches()) {
                    String id = m.group(1);
                    String prefix = id.length() > 6 ? id.substring(0, 6) : id;
                    return "https://" + uri.getHost() + "/api/webhooks/" + prefix + "***/***";
                }
            }
            return "https://" + (uri.getHost() != null ? uri.getHost() : "invalid") + "/***";
        } catch (Exception ignored) {
            return "<invalid-url>";
        }
    }

    public record ValidationResult(boolean valid, String reason) {
        static ValidationResult ok() {
            return new ValidationResult(true, "");
        }

        static ValidationResult fail(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
