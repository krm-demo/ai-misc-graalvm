package org.krmdemo.graalvm.spring;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/** The two ways to tell Spring AOT about types that only ObjectMapper touches. */
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({PlanPayload.class, Notification.Batch.class})
@ImportRuntimeHints(JacksonHints.AuditEventHints.class)
public class JacksonHints {

    static class AuditEventHints implements RuntimeHintsRegistrar {

        private final BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            bindingRegistrar.registerReflectionHints(hints.reflection(), AuditEvent.class);
        }
    }
}
