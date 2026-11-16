package org.krmdemo.graalvm.jackson;

import java.util.Objects;

/** Classic JavaBean with a default constructor and setters; registered. */
public class LegacyBean {

    private String code;
    private boolean active;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LegacyBean other
            && active == other.active
            && Objects.equals(code, other.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, active);
    }

    @Override
    public String toString() {
        return "LegacyBean[" + code + ", " + active + "]";
    }
}
