package org.krmdemo.graalvm.spring;

/** Not registered anywhere and not part of any controller signature. */
public record OrphanPayload(long id, String name) {
}
