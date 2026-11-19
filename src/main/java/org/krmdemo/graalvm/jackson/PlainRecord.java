package org.krmdemo.graalvm.jackson;

import java.time.Instant;

/** Registered for reflection in reflect-config.json. */
public record PlainRecord(long id, String name, Instant createdAt) {
}
