package com.github.orenaidaucac.royPodium.discord;

import com.github.orenaidaucac.royPodium.config.DiscordConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fully asynchronous, rate-limit-aware Discord Webhook HTTP sender.
 * Never blocks the Main Thread or Folia Region Threads, and never logs full webhook URLs.
 */
public final class DiscordWebhookSender {

    private static final Pattern JSON_RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

    private final Logger logger;
    private final HttpClient httpClient;
    private volatile ExecutorService executor;
    private volatile long lastSendEpochMs = 0L;

    public DiscordWebhookSender(Logger logger) {
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.executor = createExecutor();
    }

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "RoyPodium-Webhook");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Enqueues webhook payloads asynchronously (fire-and-forget for scheduled payouts).
     */
    public void enqueue(DiscordConfig.WebhookProfile profile, List<String> jsonPayloads, DiscordConfig cfg) {
        if (profile == null || jsonPayloads == null || jsonPayloads.isEmpty()) {
            return;
        }
        ExecutorService exec = this.executor;
        if (exec == null || exec.isShutdown()) {
            return;
        }
        exec.submit(() -> {
            for (String payload : jsonPayloads) {
                SendResult res = sendBlocking(profile.getUrl(), profile.getMaskedUrl(), payload,
                        cfg.getTimeoutSeconds(), cfg.getMaxRetries(), cfg.getMinIntervalMs());
                if (!res.success()) {
                    logger.warning("Discord webhook '" + profile.getId() + "' (" + profile.getMaskedUrl()
                            + ") failed: " + res.errorMessage());
                }
            }
        });
    }

    /**
     * Sends webhook payloads asynchronously and returns a future for admin test command feedback.
     */
    public CompletableFuture<SendResult> sendTestAsync(DiscordConfig.WebhookProfile profile,
                                                       List<String> jsonPayloads,
                                                       DiscordConfig cfg) {
        ExecutorService exec = this.executor;
        if (exec == null || exec.isShutdown()) {
            return CompletableFuture.completedFuture(SendResult.fail(0, "Webhook sender is shut down."));
        }
        return CompletableFuture.supplyAsync(() -> {
            SendResult last = SendResult.ok(204);
            for (String payload : jsonPayloads) {
                last = sendBlocking(profile.getUrl(), profile.getMaskedUrl(), payload,
                        cfg.getTimeoutSeconds(), cfg.getMaxRetries(), cfg.getMinIntervalMs());
                if (!last.success()) {
                    return last;
                }
            }
            return last;
        }, exec);
    }

    SendResult sendBlocking(String url,
                            String maskedUrl,
                            String jsonPayload,
                            int timeoutSeconds,
                            int maxRetries,
                            long minIntervalMs) {
        int attempts = Math.max(1, maxRetries + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            paceRequests(minIntervalMs);

            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(Math.max(2, timeoutSeconds)))
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .header("User-Agent", "RoyPodium/2026.1")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                lastSendEpochMs = System.currentTimeMillis();
                int code = resp.statusCode();

                if (code >= 200 && code < 300) {
                    return SendResult.ok(code);
                }

                if (code == 429 && attempt < attempts) {
                    long waitMs = parseRetryAfterMs(resp);
                    Thread.sleep(Math.min(waitMs, 15_000L));
                    continue;
                }

                if (code >= 500 && attempt < attempts) {
                    Thread.sleep(Math.min(1000L * attempt, 5_000L));
                    continue;
                }

                return SendResult.fail(code, describeHttpError(code, maskedUrl));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return SendResult.fail(0, "Webhook dispatch interrupted.");
            } catch (Exception ex) {
                if (attempt < attempts) {
                    try {
                        Thread.sleep(Math.min(1000L * attempt, 5_000L));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return SendResult.fail(0, "Webhook dispatch interrupted.");
                    }
                    continue;
                }
                return SendResult.fail(0, "Network error for " + maskedUrl + ": " + ex.getMessage());
            }
        }
        return SendResult.fail(0, "Exhausted webhook retry attempts for " + maskedUrl + ".");
    }

    private void paceRequests(long minIntervalMs) {
        long now = System.currentTimeMillis();
        long elapsed = now - lastSendEpochMs;
        if (elapsed < minIntervalMs) {
            try {
                Thread.sleep(minIntervalMs - elapsed);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static long parseRetryAfterMs(HttpResponse<String> resp) {
        String header = resp.headers().firstValue("Retry-After").orElse(null);
        if (header != null) {
            try {
                return (long) (Double.parseDouble(header.trim()) * 1000.0) + 100L;
            } catch (NumberFormatException ignored) {
            }
        }
        if (resp.body() != null) {
            Matcher m = JSON_RETRY_AFTER.matcher(resp.body());
            if (m.find()) {
                try {
                    return (long) (Double.parseDouble(m.group(1)) * 1000.0) + 100L;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 1500L;
    }

    private static String describeHttpError(int code, String maskedUrl) {
        return switch (code) {
            case 400 -> "HTTP 400 Bad Request (" + maskedUrl + ") - Check embed template or payload size.";
            case 401, 403 -> "HTTP " + code + " Unauthorized (" + maskedUrl + ") - Webhook token may be invalid.";
            case 404 -> "HTTP 404 Not Found (" + maskedUrl + ") - Webhook was deleted or URL is incorrect.";
            case 429 -> "HTTP 429 Rate Limited (" + maskedUrl + ") - Too many requests; retries exhausted.";
            default -> "HTTP " + code + " (" + maskedUrl + ") - Discord rejected or failed the request.";
        };
    }

    /**
     * Drains pending webhook requests up to {@code drainSeconds} and shuts down the worker cleanly.
     */
    public void shutdown(int drainSeconds) {
        ExecutorService exec = this.executor;
        if (exec == null) {
            return;
        }
        exec.shutdown();
        try {
            if (!exec.awaitTermination(Math.max(1, drainSeconds), TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
        } catch (InterruptedException ex) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void restartAfterReload(int drainSeconds) {
        shutdown(drainSeconds);
        this.executor = createExecutor();
    }

    public record SendResult(boolean success, int httpCode, String errorMessage) {
        public static SendResult ok(int code) {
            return new SendResult(true, code, "");
        }

        public static SendResult fail(int code, String msg) {
            return new SendResult(false, code, msg);
        }
    }
}
