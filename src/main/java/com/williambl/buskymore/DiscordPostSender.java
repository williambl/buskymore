package com.williambl.buskymore;

import com.github.mizosoft.methanol.MoreBodyHandlers;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

public class DiscordPostSender {
    public static final Logger LOGGER = LoggerFactory.getLogger(DiscordPostSender.class);
    private static final String SEND_MESSAGE_URI_TEMPLATE = "https://discord.com/api/channels/%s/messages";

    private final String token;
    private final String userAgent;
    private final HttpClient httpClient;
    private final RateLimitedExecutor rateLimitedExecutor;
    private final Map<BskyPostGetter, Config.Mapping> postGetters = new HashMap<>();

    public record Config(String token, String botOwnerUri, String botVersion, List<Mapping> mappings) {
        public record Mapping(String name, BskyPostGetter.Config getterConfig, List<String> channelIds) {}
    }

    public DiscordPostSender(Config config, ExecutorService executor) {
        this.rateLimitedExecutor = new RateLimitedExecutor(40, Duration.ofSeconds(1), executor);
        this.httpClient = HttpClient.newBuilder()
                .executor(executor)
                .build();
        this.token = config.token();
        this.userAgent = "DiscordBot (%s, %s) buskymore".formatted(config.botOwnerUri(), config.botVersion());
        for (var mapping : config.mappings) {
            this.postGetters.put(new BskyPostGetter(mapping.getterConfig(), executor), mapping);
        }
    }

    private String[] makeHeaders() {
        return new String[] {
                "User-Agent", this.userAgent,
                "Content-Type", "application/json",
                "Accept", "application/json",
                "Accept-Encoding", "gzip",
                "Authorization", "Bot "+this.token
        };
    }

    private CompletableFuture<Void> sendMessage(String channelId, String message) {
        var payload = new JsonObject();
        payload.addProperty("content", message);
        var uri = URI.create(SEND_MESSAGE_URI_TEMPLATE.formatted(channelId));
        var request = HttpRequest.newBuilder(uri)
                .headers(this.makeHeaders())
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        return CompletableFuture.runAsync(() -> {
            //fixme rn, if we wait for ratelimit to go away then we get pushed to back of the queue
            AtomicReference<CompletableFuture<Void>> future = new AtomicReference<>(CompletableFuture.completedFuture(null));
            this.httpClient.sendAsync(request, this.jsonRateLimitingBodyHandler(uri, () -> future.set(this.sendMessage(channelId, message))))
                    .thenCompose($$ -> future.get())
                    .join();
        }, this.rateLimitedExecutor);
    }

    private HttpResponse.BodyHandler<Optional<JsonElement>> jsonRateLimitingBodyHandler(URI uri, Runnable retry) {
        return MoreBodyHandlers.decoding(responseInfo -> {
            HttpResponse.BodySubscriber<String> string = HttpResponse.BodyHandlers.ofString().apply(responseInfo);
            if (responseInfo.statusCode() / 100 != 2) {
                return HttpResponse.BodySubscribers.mapping(
                        string,
                        str -> {
                            LOGGER.debug("From: {}, received non-OK status code: {}\nWith body: {}", uri, responseInfo.statusCode(), str);
                            return Optional.empty();//DataResult.error(() -> "Received non-OK status code %s (with body %s)".formatted(responseInfo.statusCode(), str));
                        });
            }
            if (responseInfo.statusCode() == 429) {
                HttpResponse.BodySubscriber<Optional<JsonElement>> subscriber = HttpResponse.BodySubscribers.mapping(
                        string,
                        str -> {
                            LOGGER.debug("From: {}, we exceeded rate limit.\nWith body: {}", uri, str);
                            return Optional.empty();
                        });
                var retryAfterHeader = responseInfo.headers().firstValue("Retry-After");
                if (retryAfterHeader.isPresent()) {
                    try {
                        double seconds = Double.parseDouble(retryAfterHeader.get());
                        Instant whenCanRetry = Instant.now().plusMillis((long) (seconds*1000));
                        this.rateLimitedExecutor.markRateLimited(whenCanRetry);
                        retry.run();
                        LOGGER.warn("We've been rate-limited for {} seconds", seconds);
                        return subscriber;
                    } catch (NumberFormatException e) {
                        LOGGER.warn("We've been rate-limited for... a non-integer number of seconds?", e);
                        return subscriber;
                    }
                } else {
                    this.rateLimitedExecutor.markRateLimited(Instant.now().plusSeconds(60));
                    retry.run();
                    LOGGER.warn("We've been rate-limited, but they won't tell us how long for :(. Going to wait 1 minute.");
                    return subscriber;
                }
            }

            var remainingHeader = responseInfo.headers().firstValue("X-RateLimit-Remaining");
            var resetAfterHeader = responseInfo.headers().firstValue("X-RateLimit-Reset-After");
            if (remainingHeader.isPresent() && resetAfterHeader.isPresent()) {
                double seconds = Double.parseDouble(resetAfterHeader.get());
                Duration untilReset = Duration.ofMillis((long) (seconds*1000));
                int remaining = Integer.parseInt(remainingHeader.get());
                this.rateLimitedExecutor.updateLimits(remaining, untilReset);
            }

            return HttpResponse.BodySubscribers.mapping(
                    string,
                    s -> {
                        try {
                            LOGGER.debug("From: {}, received {}", uri, s);
                            return Optional.of(JsonParser.parseString(s));
                        } catch (JsonParseException e) {
                            LOGGER.error("From: {}, received invalid JSON {}: ", uri, s, e);
                            return Optional.empty();
                        }
                    });
        });
    }

    public CompletableFuture<Void> run() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (var entry : this.postGetters.entrySet()) {
            var postGetter = entry.getKey();
            var mapping = entry.getValue();
            BskyPostGetter.State state;
            try {
                state = postGetter.readState();
            } catch (IOException e) {
                LOGGER.error("Failed to read state for {}", mapping.name(), e);
                continue;
            }

            LOGGER.info("Going to run post getter {}", mapping.name());
            futures.add(postGetter.run(state).thenCompose(res -> {
                LOGGER.info("Collected {} posts from {}", res.posts().size(), mapping.name());
                return this.sendPosts(res.posts(), mapping.channelIds()).thenAccept($ -> {
                    try {
                        LOGGER.info("Writing state for {}", mapping.name());
                        postGetter.writeState(res.state());
                    } catch (IOException e) {
                        LOGGER.error("Failed to write state for {}", mapping.name(), e);
                    }
                });
            }));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    public void stop() {
        LOGGER.info("Stopping");
        this.rateLimitedExecutor.close();
    }

    private CompletableFuture<Void> sendPosts(List<Post> posts, List<String> channelIds) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (var channelId : channelIds) {
            for (var post : posts) {
                futures.add(this.sendMessage(channelId, "@everyone " + makeEmbedUrl(post.uri())));
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private static String makeEmbedUrl(URI uri) {
        String did = uri.getAuthority();
        String postId = uri.getPath().split("/")[2];
        return "https://fxbsky.app/profile/%s/post/%s".formatted(did, postId);
    }
}
