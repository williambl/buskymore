package com.williambl.buskymore;

import com.google.gson.*;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) throws IOException {
        PostFilter.bootstrap();
        Path configPath;
        if (args.length == 0) {
            configPath = Path.of("./buskymore.bini");
        } else {
            configPath = Path.of(args[0]);
        }
        DiscordPostSender.Config config;
        if (configPath.getFileName().toString().endsWith(".json")) {
            var configString = Files.readString(configPath);
            var gson = makeGson();
            config = gson.fromJson(configString, DiscordPostSender.Config.class);
        } else {
            var configLines = Files.readAllLines(configPath);
            var bini = new Bini();
            config = bini.parse(DiscordPostSender.Config.class, configLines);
        }
        var sender = new DiscordPostSender(config, Executors.newVirtualThreadPerTaskExecutor());
        sender.run()
                .exceptionally(e -> {
                    sender.fail(e);
                    return null;
                })
                .thenRun(sender::stop)
                .join();
    }

    private static Gson makeGson() {
        return new GsonBuilder()
                .setStrictness(Strictness.LENIENT)
                .registerTypeAdapter(BskyPostGetter.Config.PostSource.class, new JsonDeserializer<BskyPostGetter.Config.PostSource>() {
                    private static final Type[] types = new Type[] {BskyPostGetter.Config.PostSource.User.class, BskyPostGetter.Config.PostSource.Feed.class};
                    @Override
                    public BskyPostGetter.Config.PostSource deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                        JsonParseException exception = new JsonParseException("Could not deserialise post source :(");
                        for (var clazz : types) {
                            try {
                                return context.deserialize(json, clazz);
                            } catch (JsonParseException e) {
                                exception = e;
                            }
                        }
                        throw exception;
                    }
                })
                .registerTypeAdapter(PostFilter.Fisp.class, new JsonDeserializer<PostFilter.Fisp>() {
                    @Override
                    public PostFilter.Fisp deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
                        if (json.isJsonArray()) {
                            List<PostFilter.Fisp> contents = new ArrayList<>();
                            for (JsonElement jsonElement : json.getAsJsonArray()) {
                                contents.add(context.deserialize(jsonElement, PostFilter.Fisp.class));
                            }
                            return new PostFilter.Fisp.Array(List.copyOf(contents));
                        } else if (json instanceof JsonPrimitive prim && prim.isString()) {
                            return new PostFilter.Fisp.Str(prim.getAsString());
                        } else if (json instanceof JsonPrimitive prim && prim.isBoolean()) {
                            return new PostFilter.Fisp.Bool(prim.getAsBoolean());
                        } else {
                            return new PostFilter.Fisp.Str(json.toString());
                        }
                    }
                })
                .create();
    }
}
