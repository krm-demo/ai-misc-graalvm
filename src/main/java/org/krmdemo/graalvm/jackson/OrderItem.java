package org.krmdemo.graalvm.jackson;

/** Registered for reflection in reflect-config.json. */
public record OrderItem(String sku, int quantity) {
}
