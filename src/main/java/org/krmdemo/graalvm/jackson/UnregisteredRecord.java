package org.krmdemo.graalvm.jackson;

/** Deliberately NOT registered for reflection. */
public record UnregisteredRecord(long id, String name) {
}
