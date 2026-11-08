package dev.aiw.modelgateway;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Calls Claude through the official Anthropic Java SDK. */
@Component
class AnthropicAdapter implements ModelAdapter {
    /** Models that accept server-side refusal fallbacks in "default" mode (beta server-side-fallback-2026-07-01). */
    private static final Set<String> FALLBACK_MODELS =
            Set.of("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5");

    private final Map<String, AnthropicClient> clients = new ConcurrentHashMap<>();

    @Override
    public ProviderKind kind() {
        return ProviderKind.ANTHROPIC;
    }

    @Override
    public AdapterResult call(String providerId, String baseUrl, String apiKey, ModelRequest request) {
        AnthropicClient client = clients.computeIfAbsent(providerId, id -> {
            var builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
            if (baseUrl != null && !baseUrl.isBlank()) builder.baseUrl(baseUrl);
            return builder.build();
        });

        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens());
        if (request.system() != null && !request.system().isBlank()) {
            params.system(request.system());
        }
        for (ChatTurn turn : request.turns()) {
            if (turn.role() == ChatTurn.Role.USER) params.addUserMessage(turn.text());
            else params.addAssistantMessage(turn.text());
        }
        if (request.effort() != null) {
            params.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(request.effort())).build());
        }
        if (FALLBACK_MODELS.contains(request.model())) {
            // On a safety refusal, the API re-runs the request on Anthropic's recommended fallback model.
            params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        Message message;
        try {
            message = client.messages().create(params.build());
        } catch (AnthropicServiceException e) {
            String detail = e.apiMessage().orElse(e.getMessage());
            throw new ModelCallException("Anthropic API error " + e.statusCode() + ": " + detail, e.statusCode(), e);
        } catch (AnthropicException e) {
            throw new ModelCallException("Could not reach the Anthropic API: " + e.getMessage(), 0, e);
        }

        String stop = message.stopReason().map(StopReason::asString).orElse("unknown");
        boolean refused = message.stopReason().map(StopReason.REFUSAL::equals).orElse(false);
        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"));
        return new AdapterResult(text, message.model().asString(), message.usage().inputTokens(),
                message.usage().outputTokens(), refused, stop);
    }

    @Override
    public void evict(String providerId) {
        AnthropicClient client = clients.remove(providerId);
        if (client != null) client.close();
    }
}
