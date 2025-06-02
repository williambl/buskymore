package com.williambl.buskymore;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

public record Post(URI uri, String text, Instant createdAt, Optional<String> reason, boolean hasEmbeds) {
    @Override
    public String toString() {
        return "%s [created %s]%s embed:%s text:%s".formatted(this.uri, this.createdAt, this.reason.map(rs -> " reason: " + rs).orElse(""), this.hasEmbeds, this.text);
    }
}
