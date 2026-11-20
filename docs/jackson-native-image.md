# Jackson in a GraalVM native image

Short answer: **ordinary reflection-based Jackson works in a native image, with no loss of features,
as long as every class Jackson binds is registered for reflection at build time.**
A class that is not registered is not an error at build time; it fails only when the binary runs.

Everything below is backed by tests in this repository that run on the JVM and again as a native
binary in CI ([`native-tests`](../.github/workflows/native-tests.yml) and [`json-table`](../.github/workflows/json-table.yml) workflows).
Tested with Jackson 2.19 (plain and Spring Boot 3.5) and Jackson 3.2 (`json-table`), GraalVM 25.

## Why registration is needed

`native-image` compiles only the code it can prove is reachable ("closed world"). Jackson finds
constructors, record components, getters, setters and fields **by reflection at run time**, when it
first sees a class. The compiler cannot see those calls, so unless the class is listed in the
reflection metadata, its members are not in the binary: Jackson sees a class with no usable
constructor or properties and throws an `InvalidDefinitionException`.

Nothing goes wrong at build time, so **a native test run is the only reliable check**.

## What works without any registration

- `JsonNode` trees, `Map<String, Object>`, `List<Object>`, strings, numbers, booleans:
  Jackson's own classes are reachable already.
  See [`untypedMapAndTree()`](../src/test/java/org/krmdemo/graalvm/jackson/JacksonNativeImageTest.java#L109-L119).
- Types that Jackson modules handle with their own serializers, such as `java.time.Instant` with `JavaTimeModule`
  (Jackson 2) or built in (Jackson 3), when they are fields of a registered class.

## Three ways to register

### 1. Reflection metadata file (any Java program)

A JSON file under `META-INF/native-image/<group>/<artifact>/` in the resources is picked up by
`native-image` automatically. Our files:

- [`src/main/resources/.../reflect-config.json`](../src/main/resources/META-INF/native-image/org.krm-demo/ai-misc-graalvm/reflect-config.json): records, a builder, a JavaBean, polymorphic subtypes.
- [`json-table/src/main/resources/.../reflect-config.json`](../json-table/src/main/resources/META-INF/native-image/org.example/json-table/reflect-config.json): the `Item` record and its Lombok builder.

```json
[
  {
    "name": "org.example.jsontable.Item",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true,
    "allRecordComponents": true
  },
  {
    "name": "org.example.jsontable.Item$Builder",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true
  }
]
```

Every class Jackson touches must be listed, **including nested ones**: registering a record does not
register the types of its components. GraalVM also accepts a newer single-file format,
`reachability-metadata.json`; we use `reflect-config.json` because it works with both older and current releases.

Instead of writing the file by hand you can let GraalVM's tracing agent record it while the tests
run on the JVM, then review and commit the result:

```shell
java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image/my.group/my-app \
     -jar target/my-app.jar sample.json
```

### 2. `@RegisterReflectionForBinding` (Spring Boot)

Spring's AOT step turns this annotation into reflection metadata, and it **does follow nested types**:
records, lists, maps, enums, `UUID` and `Instant` reached through the components of the named class.

```java
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({PlanPayload.class, Notification.Batch.class})
@ImportRuntimeHints(JacksonHints.AuditEventHints.class)
public class JacksonHints { ... }
```

From [`JacksonHints.java`](../spring-boot-binding/src/main/java/org/krmdemo/graalvm/spring/JacksonHints.java).
[`PlanPayload`](../spring-boot-binding/src/main/java/org/krmdemo/graalvm/spring/PlanPayload.java) is the root of
a small tree of records and an enum; only the root is named.

Types used in `@RestController` signatures (`@RequestBody`, return values) are registered by Spring
without any annotation; see [`WebhookController`](../spring-boot-binding/src/main/java/org/krmdemo/graalvm/spring/WebhookController.java).

### 3. `RuntimeHintsRegistrar` (Spring Boot, in code)

The same effect, written as code, useful when the list of types is computed:

```java
static class AuditEventHints implements RuntimeHintsRegistrar {
    private final BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        bindingRegistrar.registerReflectionHints(hints.reflection(), AuditEvent.class);
    }
}
```

## Traps we tested

| Case | Result in a native image | Where |
| :--- | :--- | :--- |
| Registered record with nested records, `List`, `Map` | works | [`Order`](../src/main/java/org/krmdemo/graalvm/jackson/Order.java), [test](../src/test/java/org/krmdemo/graalvm/jackson/JacksonNativeImageTest.java#L57-L62) |
| `TypeReference<List<Order>>` | works if `Order` is registered | [test](../src/test/java/org/krmdemo/graalvm/jackson/JacksonNativeImageTest.java#L64-L72) |
| Polymorphism: `@JsonTypeInfo` + `@JsonSubTypes` | works if **every subtype** is registered | [`Shape`](../src/main/java/org/krmdemo/graalvm/jackson/Shape.java), [test](../src/test/java/org/krmdemo/graalvm/jackson/JacksonNativeImageTest.java#L74-L82) |
| Builder: `@JsonDeserialize(builder = ...)` | works if the builder class is registered too | [`Person`](../src/main/java/org/krmdemo/graalvm/jackson/Person.java) |
| Lombok `@Jacksonized @Builder` with a catch-all `@JsonAnySetter` | works with the record and `Item$Builder` registered | [`Item`](../json-table/src/main/java/org/example/jsontable/Item.java), the whole `json-table` tool |
| Classic JavaBean with setters | works | [`LegacyBean`](../src/main/java/org/krmdemo/graalvm/jackson/LegacyBean.java) |
| Registered record whose nested type is **not** registered | **fails** | [`Basket`](../src/main/java/org/krmdemo/graalvm/jackson/Basket.java), [test](../src/test/java/org/krmdemo/graalvm/jackson/JacksonNativeImageTest.java#L138-L143) |
| `convertValue(map, UnregisteredRecord.class)` | **fails**; `convertValue` to a registered type works | [test](../src/test/java/org/krmdemo/graalvm/jackson/JacksonNativeImageTest.java#L145-L150) |
| Spring: `@RegisterReflectionForBinding(Root.class)` where the root has `@JsonSubTypes` subtypes | **fails**: Spring does not follow `@JsonSubTypes`, name each subtype | [`Notification`](../spring-boot-binding/src/main/java/org/krmdemo/graalvm/spring/Notification.java), [test](../spring-boot-binding/src/test/java/org/krmdemo/graalvm/spring/SpringBindingNativeImageTest.java#L91-L96) |

The "fails" rows are asserted too: if a future GraalVM or Spring release makes one of them work,
the test fails and the table has to be updated.

**Not usable at all:** the Afterburner and Blackbird modules. They generate bytecode at run time,
which a native image cannot load. Drop them from native builds; plain Jackson is fast enough there.

## How the tests are built

The same JUnit class runs twice, `mvn test` on the JVM and `mvn -Pnative test` as a native binary
(GraalVM's `native-maven-plugin`). The test knows where it runs from a system property that only
exists inside a native image:

```java
private static final boolean NATIVE = System.getProperty("org.graalvm.nativeimage.imagecode") != null;
```

Each case is either `mustWork(...)` (both modes) or `needsRegistration(...)` (works on the JVM, must
fail in the native binary). See [`JacksonNativeImageTest`](../src/test/java/org/krmdemo/graalvm/jackson/JacksonNativeImageTest.java)
and [`SpringBindingNativeImageTest`](../spring-boot-binding/src/test/java/org/krmdemo/graalvm/spring/SpringBindingNativeImageTest.java).
The per-case results are printed in the summary of each `native-tests` run.

## Other libraries in the same binary

Picocli has the same reflection needs as Jackson, and solves them at compile time: its annotation
processor `picocli-codegen` writes the metadata for every `@Command` class during `javac`.
See the `annotationProcessorPaths` in [`json-table/pom.xml`](../json-table/pom.xml).

## Checklist

1. List every class Jackson binds, including nested types, subtypes and builder classes.
2. Register them: a metadata file, `@RegisterReflectionForBinding`, or a `RuntimeHintsRegistrar`.
3. Remove Afterburner and Blackbird from the native build.
4. Run the tests as a native binary in CI, with at least one JSON round trip per payload type.
