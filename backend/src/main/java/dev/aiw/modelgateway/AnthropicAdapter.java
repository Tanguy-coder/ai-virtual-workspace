package dev.aiw.modelgateway;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import java.util.ArrayList;
import java.util.List;
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

    private AnthropicClient client(String providerId, String baseUrl, String apiKey) {
        return clients.computeIfAbsent(providerId, id -> {
            var builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
            if (baseUrl != null && !baseUrl.isBlank()) builder.baseUrl(baseUrl);
            return builder.build();
        });
    }

    @Override
    public List<String> listModels(String providerId, String baseUrl, String apiKey) {
        try {
            List<String> ids = new ArrayList<>();
            client(providerId, baseUrl, apiKey).models().list().autoPager().forEach(m -> ids.add(m.id()));
            return ids.stream().sorted().toList();
        } catch (AnthropicServiceException e) {
            throw new ModelCallException("Anthropic API error " + e.statusCode() + ": " + e.apiMessage().orElse(e.getMessage()),
                    e.statusCode(), e);
        } catch (AnthropicException e) {
            throw new ModelCallException("Could not reach the Anthropic API: " + e.getMessage(), 0, e);
        }
    }

    @Override
    public AdapterResult call(String providerId, String baseUrl, String apiKey, ModelRequest request) {
        AnthropicClient client = client(providerId, baseUrl, apiKey);

        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens());
        // Cache breakpoints (docs/09): the stable prefix, then the whole system prompt. Prefixes shorter than the
        // provider's minimum are simply not cached.
        boolean mark = request.cache() != ModelRequest.Cache.NONE;
        List<TextBlockParam> system = new ArrayList<>();
        for (String block : new String[] {request.systemPrefix(), request.system()}) {
            if (block == null || block.isBlank()) continue;
            TextBlockParam.Builder b = TextBlockParam.builder().text(block);
            if (mark) b.cacheControl(CacheControlEphemeral.builder().build());
            system.add(b.build());
        }
        if (!system.isEmpty()) params.systemOfTextBlockParams(system);
        if (request.cache() == ModelRequest.Cache.CONVERSATION) {
            // Automatic caching: the breakpoint moves to the last block, so the next turn re-reads the conversation.
            params.cacheControl(CacheControlEphemeral.builder().build());
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
                message.usage().outputTokens(), refused, stop,
                message.usage().cacheCreationInputTokens().orElse(0L), message.usage().cacheReadInputTokens().orElse(0L));
    }

    @Override
    public void evict(String providerId) {
        AnthropicClient client = clients.remove(providerId);
        if (client != null) client.close();
    }
}
