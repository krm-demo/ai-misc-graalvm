package org.krmdemo.graalvm.spring;

import java.time.Instant;

/** Registered programmatically by {@link JacksonHints.AuditEventHints}. */
public record AuditEvent(String actor, String action, Instant at) {
}
