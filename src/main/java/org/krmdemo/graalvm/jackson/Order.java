package org.krmdemo.graalvm.jackson;

import java.util.List;
import java.util.Map;

/** Registered, and so is the nested {@link OrderItem}. */
public record Order(String orderId, List<OrderItem> items, Map<String, String> tags) {
}
