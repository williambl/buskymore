package com.williambl.buskymore;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class DiscordPostSender {
    public static final Logger LOGGER = LoggerFactory.getLogger(DiscordPostSender.class);

    private final JDA jda;
    private final Map<BskyPostGetter, Config.Mapping> postGetters = new HashMap<>();

    public record Config(String token, List<Mapping> mappings) {
        public record Mapping(String name, BskyPostGetter.Config getterConfig, List<String> channelIds) {}
    }

    public DiscordPostSender(Config config, ExecutorService executor) {
        this.jda = JDABuilder.createLight(config.token())
                .build();
        for (var mapping : config.mappings) {
            this.postGetters.put(new BskyPostGetter(mapping.getterConfig(), executor), mapping);
        }
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
        this.jda.shutdown();
    }

    private CompletableFuture<Void> sendPosts(List<Post> posts, List<String> channelIds) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (var channelId : channelIds) {
            var channel = this.jda.getChannelById(TextChannel.class, channelId);

            for (var post : posts) {
                if (channel != null) {
                    futures.add(channel.sendMessage("@everyone " + makeEmbedUrl(post.uri())).submit());
                }
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
