# ai-misc-graalvm
Miscellaneous examples with Graal-VM

## Jackson in a GraalVM native image

`JacksonNativeImageTest` runs a set of Jackson use cases twice: on the JVM and as a native binary.

| Kind of case | JVM | Native image |
| --- | --- | --- |
| Types listed in `reflect-config.json` (records, nested records, `TypeReference`, polymorphic subtypes, builder, JavaBean) | must work | must work |
| Untyped `Map<String, Object>` / `JsonNode` | must work | must work |
| Types that are not registered, including a nested one inside a registered record | must work | must fail |

A "must fail" test fails the build if the case starts working, so the table stays true.

The registration lives in
`src/main/resources/META-INF/native-image/org.krm-demo/ai-misc-graalvm/reflect-config.json`.

### Run

```
mvn test            # JVM only
mvn -Pnative test   # JVM, then native (needs GraalVM with native-image)
```

Per-case results are written to `target/jackson-cases-jvm.txt` and `target/jackson-cases-native.txt`.
The `native-tests` GitHub workflow runs both and prints the results in the run summary.

## Spring Boot AOT and Jackson

`spring-boot-binding/` is a separate Maven project (Spring Boot parent) with `SpringBindingNativeImageTest`.
Every case calls Spring Boot's `ObjectMapper` by hand, as a DAO would for a JSON column.

| How the type is known to Spring AOT | Native image |
| --- | --- |
| `@RegisterReflectionForBinding(Root.class)`, nested types not named | must work |
| `RuntimeHintsRegistrar` using `BindingReflectionHintsRegistrar` | must work |
| `@RequestBody` / return type of a `@RestController`, nothing registered by hand | must work |
| Not known at all | must fail |
| `@JsonSubTypes` subtypes behind a registered root | observed, not asserted: fails on Spring Boot 3.5.6, so name every subtype |

```
cd spring-boot-binding
mvn test               # JVM only
mvn -PnativeTest test  # JVM, then native
```

Results are written to `spring-boot-binding/target/spring-cases-*.txt` and shown in the workflow run summary.

## json-table

`json-table/` is a small command-line tool (Jackson 3, Picocli) that prints a JSON array of objects as a table.

```
cd json-table
mvn package                 # target/json-table-1.0.jar
mvn -Pnative package        # also target/json-table (needs GraalVM)

./run-jvm.sh sample.json
cat sample.json | ./run-native.sh -
java -jar target/json-table-1.0.jar --help
```

- Without arguments, or with `--help`, it prints what input it expects.
- Errors are one line (`FILE NOT FOUND:`, `INVALID JSON:`, `INVALID DATA:`, `USAGE ERROR:` ...); add `--debug` for the stack trace.
- `json-table-jq.sh` prints the same table with `jq` only.
- `smoke-test.sh` checks help, exit codes and error messages for either build.
- The `json-table-bench` workflow times several JVMs, the native executable and the jq script with `hyperfine`, for three input sizes.
