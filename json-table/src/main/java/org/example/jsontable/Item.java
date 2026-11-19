package org.example.jsontable;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import tools.jackson.databind.JsonNode;

/**
 * One element of the input array. Replace id / name / createdAt with your real fields.
 * Every JSON property that matches none of them lands in {@link #unknown()}, in input order.
 */
@Jacksonized
@Builder(builderClassName = "Builder")
public record Item(
        String id,
        String name,
        Instant createdAt,
        Map<String, JsonNode> unknown) {

    public Item {
        unknown = unknown == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(unknown));
    }

    /** Lombok completes this class: it adds the fields, the other setters and build(). */
    public static class Builder {

        /** Fallback: Jackson calls this for every property that has no setter in the builder. */
        @JsonAnySetter
        public Builder unknownProperty(String name, JsonNode value) {
            if (unknown == null) {
                unknown = new LinkedHashMap<>();
            }
            unknown.put(name, value);
            return this;
        }

        /** Hidden from Jackson, so an input property literally named "unknown" goes to the fallback too. */
        @JsonIgnore
        public Builder unknown(Map<String, JsonNode> map) {
            this.unknown = map;
            return this;
        }
    }
}
