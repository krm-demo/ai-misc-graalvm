package org.krmdemo.graalvm.spring;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nothing is registered by hand for {@link WebhookRequest} and {@link WebhookAck}:
 * they appear only in this controller's signature.
 */
@RestController
public class WebhookController {

    public record WebhookRequest(String source, int attempt) {
    }

    public record WebhookAck(String source, boolean accepted) {
    }

    @PostMapping("/webhook")
    public WebhookAck handle(@RequestBody WebhookRequest request) {
        return new WebhookAck(request.source(), request.attempt() < 3);
    }
}
