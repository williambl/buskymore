package com.williambl.buskymore;

import com.google.gson.*;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) throws IOException {
        Path configPath;
        if (args.length == 0) {
            configPath = Path.of("./buskymore.json");
        } else {
            configPath = Path.of(args[0]);
        }
        var configString = Files.readString(configPath);
        var gson = makeGson();
        var config = gson.fromJson(configString, DiscordPostSender.Config.class);
        var sender = new DiscordPostSender(config, Executors.newVirtualThreadPerTaskExecutor());
        sender.run()
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
                .create();
    }
}
