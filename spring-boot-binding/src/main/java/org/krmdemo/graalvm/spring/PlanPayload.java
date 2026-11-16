package org.krmdemo.graalvm.spring;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stands in for a JSONB payload that a DAO reads and writes by calling ObjectMapper itself.
 * Only this root type is named in {@code @RegisterReflectionForBinding};
 * {@link PlanStep}, {@link StepDetails} and {@link PlanStatus} are reached through its components.
 */
public record PlanPayload(
    UUID planId,
    PlanStatus planStatus,
    List<PlanStep> steps,
    Map<String, String> attributes,
    Instant createdAt) {

    public enum PlanStatus { DRAFT, ACTIVE, DONE }

    public record PlanStep(String name, int order, StepDetails details) {
    }

    public record StepDetails(String note, List<String> labels) {
    }
}
