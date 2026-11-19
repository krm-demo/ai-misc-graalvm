package org.krmdemo.graalvm.jackson;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each test is one Jackson use case. The same tests run on the JVM and as a native binary.
 * <ul>
 *   <li>Cases whose types are listed in {@code reflect-config.json} must work in both modes.</li>
 *   <li>Cases that involve an unregistered type must work on the JVM and must NOT work
 *       in the native binary. If one of them starts working there, the test fails,
 *       so the expectation documented here stays honest.</li>
 * </ul>
 */
class JacksonNativeImageTest {

    /** Set by GraalVM inside a native image ("runtime" when the binary is executing). */
    private static final boolean NATIVE = System.getProperty("org.graalvm.nativeimage.imagecode") != null;

    private static final String MODE = NATIVE ? "native" : "jvm";

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();

    private static final List<String> REPORT = new ArrayList<>();

    // ---------------------------------------------------------------- registered types

    @Test
    void registeredRecord() {
        PlainRecord value = new PlainRecord(42L, "answer", Instant.parse("2026-11-16T12:00:00Z"));
        mustWork("registered record (with java.time field)",
            () -> roundTrip(value, PlainRecord.class));
    }

    @Test
    void registeredNestedRecords() {
        Order value = sampleOrder("A-1");
        mustWork("registered record with nested List<record> and Map",
            () -> roundTrip(value, Order.class));
    }

    @Test
    void registeredTypeBehindTypeReference() {
        List<Order> value = List.of(sampleOrder("A-1"), sampleOrder("A-2"));
        mustWork("TypeReference<List<Order>> of registered types", () -> {
            String json = MAPPER.writeValueAsString(value);
            List<Order> back = MAPPER.readValue(json, new TypeReference<List<Order>>() { });
            return value.equals(back);
        });
    }

    @Test
    void registeredPolymorphicSubtypes() {
        List<Shape> value = List.of(new Shape.Circle(1.5), new Shape.Square(2.0));
        mustWork("@JsonTypeInfo/@JsonSubTypes with registered subtypes", () -> {
            String json = MAPPER.writerFor(new TypeReference<List<Shape>>() { }).writeValueAsString(value);
            List<Shape> back = MAPPER.readValue(json, new TypeReference<List<Shape>>() { });
            return json.contains("\"type\":\"circle\"") && value.equals(back);
        });
    }

    @Test
    void registeredBuilder() {
        Person value = Person.builder().firstName("Ada").lastName("Lovelace").age(36).build();
        mustWork("@JsonDeserialize(builder) with registered builder",
            () -> roundTrip(value, Person.class));
    }

    @Test
    void registeredJavaBean() {
        LegacyBean value = new LegacyBean();
        value.setCode("X1");
        value.setActive(true);
        mustWork("JavaBean with setters, registered",
            () -> roundTrip(value, LegacyBean.class));
    }

    @Test
    void convertMapToRegisteredRecord() {
        Map<String, Object> map = Map.of("sku", "S-1", "quantity", 3);
        mustWork("convertValue(Map -> registered record)",
            () -> new OrderItem("S-1", 3).equals(MAPPER.convertValue(map, OrderItem.class)));
    }

    // ---------------------------------------------------------------- no user types at all

    @Test
    void untypedMapAndTree() {
        String json = "{\"id\":7,\"name\":\"seven\",\"items\":[{\"sku\":\"S-1\"}]}";
        mustWork("untyped Map<String,Object> and JsonNode (nothing registered)", () -> {
            Map<String, Object> map = MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
            JsonNode tree = MAPPER.readTree(json);
            return Integer.valueOf(7).equals(map.get("id"))
                && "S-1".equals(tree.path("items").path(0).path("sku").asText())
                && MAPPER.readTree(MAPPER.writeValueAsString(map)).equals(tree);
        });
    }

    // ---------------------------------------------------------------- unregistered types

    @Test
    void unregisteredRecordDeserialization() {
        String json = "{\"id\":1,\"name\":\"one\"}";
        needsRegistration("deserialize an unregistered record",
            () -> new UnregisteredRecord(1L, "one").equals(MAPPER.readValue(json, UnregisteredRecord.class)));
    }

    @Test
    void unregisteredRecordSerialization() {
        needsRegistration("serialize an unregistered record", () -> {
            String json = MAPPER.writeValueAsString(new UnregisteredRecord(1L, "one"));
            return MAPPER.readTree("{\"id\":1,\"name\":\"one\"}").equals(MAPPER.readTree(json));
        });
    }

    @Test
    void registeredRecordWithUnregisteredNestedType() {
        Basket value = new Basket("B-1", List.of(new UnregisteredRecord(1L, "one")));
        needsRegistration("registered record whose nested type is unregistered",
            () -> roundTrip(value, Basket.class));
    }

    @Test
    void convertMapToUnregisteredRecord() {
        Map<String, Object> map = Map.of("id", 1, "name", "one");
        needsRegistration("convertValue(Map -> unregistered record)",
            () -> new UnregisteredRecord(1L, "one").equals(MAPPER.convertValue(map, UnregisteredRecord.class)));
    }

    // ---------------------------------------------------------------- helpers

    private static Order sampleOrder(String id) {
        return new Order(id, List.of(new OrderItem("S-1", 2), new OrderItem("S-2", 1)), Map.of("channel", "web"));
    }

    private static <T> boolean roundTrip(T value, Class<T> type) throws Exception {
        String json = MAPPER.writeValueAsString(value);
        T back = MAPPER.readValue(json, type);
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
        System.out.println("[jackson-case] " + line);
    }

    @AfterAll
    static void writeReport() throws IOException {
        Path dir = Files.isDirectory(Path.of("target")) ? Path.of("target") : Path.of(".");
        List<String> sorted = new ArrayList<>(REPORT);
        sorted.sort(null);
        Files.write(dir.resolve("jackson-cases-" + MODE + ".txt"), sorted);
    }
}
