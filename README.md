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
