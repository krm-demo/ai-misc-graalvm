package org.krmdemo.graalvm.jackson;

import java.util.List;

/** Registered itself, but the nested {@link UnregisteredRecord} is not. */
public record Basket(String basketId, List<UnregisteredRecord> items) {
}
