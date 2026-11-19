package org.krmdemo.graalvm.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.krmdemo.graalvm.spring.Notification.Batch;
import org.krmdemo.graalvm.spring.Notification.Email;
import org.krmdemo.graalvm.spring.Notification.Sms;
import org.krmdemo.graalvm.spring.PlanPayload.PlanStatus;
import org.krmdemo.graalvm.spring.PlanPayload.PlanStep;
import org.krmdemo.graalvm.spring.PlanPayload.StepDetails;
import org.krmdemo.graalvm.spring.WebhookController.WebhookAck;
import org.krmdemo.graalvm.spring.WebhookController.WebhookRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every case calls Spring Boot's ObjectMapper by hand, the way a DAO would for a JSON column.
 * What differs is how (or whether) the type was made known to Spring AOT.
 * The same tests run on the JVM and as a native binary.
 */
@SpringBootTest
class SpringBindingNativeImageTest {

    /** Set by GraalVM inside a native image. */
    private static final boolean NATIVE = System.getProperty("org.graalvm.nativeimage.imagecode") != null;

    private static final String MODE = NATIVE ? "native" : "jvm";

    private static final List<String> REPORT = new ArrayList<>();

    @Autowired
    private ObjectMapper mapper;

    @Test
    void registerReflectionForBindingCoversNestedTypes() {
        PlanPayload value = new PlanPayload(
            UUID.fromString("0193a5b0-0000-7000-8000-000000000001"),
            PlanStatus.ACTIVE,
            List.of(
                new PlanStep("extract", 1, new StepDetails("first", List.of("a", "b"))),
                new PlanStep("load", 2, new StepDetails("second", List.of()))),
            Map.of("owner", "team-a"),
            Instant.parse("2026-11-16T12:00:00Z"));
        mustWork("@RegisterReflectionForBinding(root): root + nested records, enum, UUID, Instant",
            () -> roundTrip(value, PlanPayload.class));
    }

    @Test
    void runtimeHintsRegistrar() {
        AuditEvent value = new AuditEvent("alice", "approve", Instant.parse("2026-11-16T12:00:00Z"));
        mustWork("RuntimeHintsRegistrar + BindingReflectionHintsRegistrar",
            () -> roundTrip(value, AuditEvent.class));
    }

    @Test
    void controllerRequestBodyType() {
        WebhookRequest value = new WebhookRequest("github", 1);
        mustWork("@RequestBody type of a @RestController (no manual registration)",
            () -> roundTrip(value, WebhookRequest.class));
    }

    @Test
    void controllerReturnType() {
        WebhookAck value = new WebhookAck("github", true);
        mustWork("return type of a @RestController (no manual registration)",
            () -> roundTrip(value, WebhookAck.class));
    }

    @Test
    void typeUnknownToSpring() {
        OrphanPayload value = new OrphanPayload(1L, "one");
        needsRegistration("type used only through ObjectMapper, registered nowhere",
            () -> roundTrip(value, OrphanPayload.class));
    }

    @Test
    void polymorphicSubtypesBehindRegisteredRoot() {
        Batch value = new Batch("B-1", List.of(new Email("a@example.org", "hi"), new Sms("+10000000000", "yo")));
        observe("@RegisterReflectionForBinding(root) with @JsonSubTypes subtypes not named",
            () -> roundTrip(value, Batch.class));
    }

    // ---------------------------------------------------------------- helpers

    private <T> boolean roundTrip(T value, Class<T> type) throws Exception {
        String json = mapper.writeValueAsString(value);
        T back = mapper.readValue(json, type);
        return Objects.equals(value, back);
    }

    /** The case must work in both modes. */
    private static void mustWork(String caseName, Callable<Boolean> attempt) {
        String failure = run(attempt);
        log(caseName, "must work", failure);
        assertTrue(failure == null, () -> caseName + " failed in " + MODE + " mode: " + failure);
    }

    /** The case must work on the JVM and must fail in a native image. */
    private static void needsRegistration(String caseName, Callable<Boolean> attempt) {
        String failure = run(attempt);
        log(caseName, NATIVE ? "must fail" : "must work", failure);
        if (NATIVE) {
            assertFalse(failure == null,
                () -> caseName + " unexpectedly works in a native image without registration");
        } else {
            assertTrue(failure == null, () -> caseName + " failed on the JVM: " + failure);
        }
    }

    /** Outcome in a native image is not asserted, only reported. It must still work on the JVM. */
    private static void observe(String caseName, Callable<Boolean> attempt) {
        String failure = run(attempt);
        log(caseName, NATIVE ? "observed" : "must work", failure);
        if (!NATIVE) {
            assertTrue(failure == null, () -> caseName + " failed on the JVM: " + failure);
        }
    }

    /** Returns null when the attempt succeeded, otherwise a one-line description of the failure. */
    private static String run(Callable<Boolean> attempt) {
        try {
            return Boolean.TRUE.equals(attempt.call()) ? null : "round trip produced a different value";
        } catch (Throwable t) {
            String message = String.valueOf(t.getMessage()).lines().findFirst().orElse("");
            if (message.length() > 160) {
                message = message.substring(0, 160) + "...";
            }
            return t.getClass().getName() + ": " + message;
        }
    }

    private static synchronized void log(String caseName, String expectation, String failure) {
        String line = String.format("%-6s | %-9s | %-6s | %s%s",
            MODE, expectation, failure == null ? "OK" : "FAILED", caseName,
            failure == null ? "" : "\n         -> " + failure);
        REPORT.add(line);
        System.out.println("[spring-case] " + line);
    }

    @AfterAll
    static void writeReport() throws IOException {
        Path dir = Files.isDirectory(Path.of("target")) ? Path.of("target") : Path.of(".");
        List<String> sorted = new ArrayList<>(REPORT);
        sorted.sort(null);
        Files.write(dir.resolve("spring-cases-" + MODE + ".txt"), sorted);
    }
}
