package org.krmdemo.graalvm.spring;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Polymorphic type. Only {@link Batch} is named in {@code @RegisterReflectionForBinding};
 * whether Spring also registers the subtypes is what the test observes.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Notification.Email.class, name = "email"),
    @JsonSubTypes.Type(value = Notification.Sms.class, name = "sms")
})
public sealed interface Notification {

    record Email(String address, String subject) implements Notification {
    }

    record Sms(String phone, String text) implements Notification {
    }

    record Batch(String batchId, List<Notification> items) {
    }
}
