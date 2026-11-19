package org.krmdemo.graalvm.jackson;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Polymorphic type; the interface and both subtypes are registered. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Shape.Circle.class, name = "circle"),
    @JsonSubTypes.Type(value = Shape.Square.class, name = "square")
})
public sealed interface Shape {

    record Circle(double radius) implements Shape {
    }

    record Square(double side) implements Shape {
    }
}
