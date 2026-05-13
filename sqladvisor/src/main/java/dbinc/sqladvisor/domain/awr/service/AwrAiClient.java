package dbinc.sqladvisor.domain.awr.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class AwrAiClient {

    private static final int MAX_EMBEDDING_TEXT_LENGTH = 12_000;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AwrAiConfigService configService;

    @Value("${awr.ai.timeout-seconds:45}")
    private int timeoutSeconds;

    public AwrAiClient(ObjectMapper objectMapper, AwrAiConfigService configService) {
        this.objectMapper = objectMapper;
        this.configService = configService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isExternalLlmEnabled() {
        AwrAiConfigService.ActiveAiSettings settings = configService.activeSettings();
        return ("openai".equals(settings.llmProvider()) && hasText(settings.openaiApiKey()))
                || ("gemini".equals(settings.llmProvider()) && hasText(settings.geminiApiKey()))
                || ("ollama".equals(settings.llmProvider()) && hasText(settings.ollamaBaseUrl()) && hasText(settings.ollamaChatModel()));
    }

    public boolean isEmbeddingEnabled() {
        AwrAiConfigService.ActiveAiSettings settings = configService.activeSettings();
        return ("openai".equals(settings.embeddingProvider()) && hasText(settings.openaiApiKey()))
                || ("gemini".equals(settings.embeddingProvider()) && hasText(settings.geminiApiKey()))
                || ("ollama".equals(settings.embeddingProvider()) && hasText(settings.ollamaBaseUrl()) && hasText(settings.ollamaEmbeddingModel()));
    }

    public String activeLlmModel() {
        AwrAiConfigService.ActiveAiSettings settings = configService.activeSettings();
        return switch (settings.llmProvider()) {
            case "openai" -> "openai/" + settings.openaiChatModel();
            case "gemini" -> "gemini/" + settings.geminiChatModel();
            case "ollama" -> "ollama/" + settings.ollamaChatModel();
            default -> "rule-based-local-advisor";
        };
    }

    public String activeEmbeddingModel() {
        AwrAiConfigService.ActiveAiSettings settings = configService.activeSettings();
        return switch (settings.embeddingProvider()) {
            case "openai" -> "openai/" + settings.openaiEmbeddingModel();
            case "gemini" -> "gemini/" + settings.geminiEmbeddingModel();
            case "ollama" -> "ollama/" + settings.ollamaEmbeddingModel();
            default -> "none";
        };
    }

    public Optional<LlmResult> complete(String systemPrompt, String userPrompt) {
        AwrAiConfigService.ActiveAiSettings settings = configService.activeSettings();
        try {
            if ("openai".equals(settings.llmProvider()) && hasText(settings.openaiApiKey())) {
                return Optional.of(callOpenAiChat(settings, systemPrompt, userPrompt));
            }
            if ("gemini".equals(settings.llmProvider()) && hasText(settings.geminiApiKey())) {
                return Optional.of(callGeminiChat(settings, systemPrompt, userPrompt));
            }
            if ("ollama".equals(settings.llmProvider()) && hasText(settings.ollamaBaseUrl()) && hasText(settings.ollamaChatModel())) {
                return Optional.of(callOllamaChat(settings, systemPrompt, userPrompt));
            }
        } catch (RuntimeException exception) {
            log.warn("External LLM call failed. Falling back to local advisor: {}", exception.getMessage());
        }
        return Optional.empty();
    }

    public Optional<EmbeddingResult> embed(String text) {
        AwrAiConfigService.ActiveAiSettings settings = configService.activeSettings();
        String normalizedText = abbreviate(text, MAX_EMBEDDING_TEXT_LENGTH);
        try {
            if ("openai".equals(settings.embeddingProvider()) && hasText(settings.openaiApiKey())) {
                List<Double> vector = normalizeDimension(callOpenAiEmbedding(settings, normalizedText), settings.embeddingDimension());
                return Optional.of(new EmbeddingResult("openai", settings.openaiEmbeddingModel(), settings.embeddingDimension(), vector));
            }
            if ("gemini".equals(settings.embeddingProvider()) && hasText(settings.geminiApiKey())) {
                List<Double> vector = normalizeDimension(callGeminiEmbedding(settings, normalizedText), settings.embeddingDimension());
                return Optional.of(new EmbeddingResult("gemini", settings.geminiEmbeddingModel(), settings.embeddingDimension(), vector));
            }
            if ("ollama".equals(settings.embeddingProvider()) && hasText(settings.ollamaBaseUrl()) && hasText(settings.ollamaEmbeddingModel())) {
                List<Double> vector = normalizeDimension(callOllamaEmbedding(settings, normalizedText), settings.embeddingDimension());
                return Optional.of(new EmbeddingResult("ollama", settings.ollamaEmbeddingModel(), settings.embeddingDimension(), vector));
            }
        } catch (RuntimeException exception) {
            log.warn("Embedding call failed. Chunk will be stored without vector: {}", exception.getMessage());
        }
        return Optional.empty();
    }

    private LlmResult callOpenAiChat(AwrAiConfigService.ActiveAiSettings settings, String systemPrompt, String userPrompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", settings.openaiChatModel());
        body.put("temperature", 0.2);
        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", systemPrompt);
        messages.addObject()
                .put("role", "user")
                .put("content", userPrompt);

        JsonNode response = postJson(
                URI.create("https://api.openai.com/v1/chat/completions"),
                body,
                "Bearer " + settings.openaiApiKey()
        );
        String content = response.path("choices").path(0).path("message").path("content").asText("");
        if (!hasText(content)) {
            throw new IllegalStateException("OpenAI response did not contain message content");
        }
        return new LlmResult("openai", settings.openaiChatModel(), content);
    }

    private LlmResult callGeminiChat(AwrAiConfigService.ActiveAiSettings settings, String systemPrompt, String userPrompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("systemInstruction")
                .putArray("parts")
                .addObject()
                .put("text", systemPrompt);
        ArrayNode contents = body.putArray("contents");
        ObjectNode content = contents.addObject();
        content.put("role", "user");
        content.putArray("parts")
                .addObject()
                .put("text", userPrompt);
        body.putObject("generationConfig").put("temperature", 0.2);

        JsonNode response = postJson(
                URI.create("https://generativelanguage.googleapis.com/v1beta/" + geminiModelPath(settings.geminiChatModel()) + ":generateContent?key=" + settings.geminiApiKey()),
                body,
                null
        );
        StringBuilder builder = new StringBuilder();
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            String text = part.path("text").asText("");
            if (hasText(text)) {
                builder.append(text);
            }
        }
        String contentText = builder.toString();
        if (!hasText(contentText)) {
            throw new IllegalStateException("Gemini response did not contain text content");
        }
        return new LlmResult("gemini", settings.geminiChatModel(), contentText);
    }

    private LlmResult callOllamaChat(AwrAiConfigService.ActiveAiSettings settings, String systemPrompt, String userPrompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", settings.ollamaChatModel());
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", systemPrompt);
        messages.addObject()
                .put("role", "user")
                .put("content", userPrompt);
        body.putObject("options").put("temperature", 0.2);

        JsonNode response = postJson(
                ollamaUri(settings.ollamaBaseUrl(), "/api/chat"),
                body,
                null
        );
        String content = response.path("message").path("content").asText("");
        if (!hasText(content)) {
            throw new IllegalStateException("Ollama response did not contain message content");
        }
        return new LlmResult("ollama", settings.ollamaChatModel(), content);
    }

    private List<Double> callOpenAiEmbedding(AwrAiConfigService.ActiveAiSettings settings, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", settings.openaiEmbeddingModel());
        body.put("input", text);
        if (settings.openaiEmbeddingModel().startsWith("text-embedding-3")) {
            body.put("dimensions", settings.embeddingDimension());
        }
        JsonNode response = postJson(
                URI.create("https://api.openai.com/v1/embeddings"),
                body,
                "Bearer " + settings.openaiApiKey()
        );
        return doubles(response.path("data").path(0).path("embedding"));
    }

    private List<Double> callGeminiEmbedding(AwrAiConfigService.ActiveAiSettings settings, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", geminiModelPath(settings.geminiEmbeddingModel()));
        body.putObject("content")
                .putArray("parts")
                .addObject()
                .put("text", text);
        body.put("outputDimensionality", settings.embeddingDimension());

        JsonNode response = postJson(
                URI.create("https://generativelanguage.googleapis.com/v1beta/" + geminiModelPath(settings.geminiEmbeddingModel()) + ":embedContent?key=" + settings.geminiApiKey()),
                body,
                null
        );
        return doubles(response.path("embedding").path("values"));
    }

    private List<Double> callOllamaEmbedding(AwrAiConfigService.ActiveAiSettings settings, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", settings.ollamaEmbeddingModel());
        body.put("input", text);
        body.put("truncate", true);

        JsonNode response = postJson(
                ollamaUri(settings.ollamaBaseUrl(), "/api/embed"),
                body,
                null
        );
        return doubles(response.path("embeddings").path(0));
    }

    private JsonNode postJson(URI uri, JsonNode body, String authorizationHeader) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            if (authorizationHeader != null) {
                requestBuilder.header("Authorization", authorizationHeader);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " from AI provider: " + abbreviate(response.body(), 400));
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("AI provider response parse failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI provider call interrupted", exception);
        }
    }

    private List<Double> doubles(JsonNode array) {
        List<Double> values = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return values;
        }
        for (JsonNode value : array) {
            values.add(value.asDouble());
        }
        return values;
    }

    private List<Double> normalizeDimension(List<Double> vector, int embeddingDimension) {
        if (vector.isEmpty()) {
            throw new IllegalStateException("Embedding provider returned an empty vector");
        }
        List<Double> normalized = new ArrayList<>(embeddingDimension);
        for (int index = 0; index < embeddingDimension; index++) {
            normalized.add(index < vector.size() ? vector.get(index) : 0.0);
        }
        return normalized;
    }

    private String geminiModelPath(String model) {
        return model.startsWith("models/") ? model : "models/" + model;
    }

    private URI ollamaUri(String baseUrl, String endpoint) {
        String normalizedBaseUrl = baseUrl.trim().replaceAll("/+$", "");
        String normalizedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        if (normalizedBaseUrl.endsWith("/api") && normalizedEndpoint.startsWith("/api/")) {
            normalizedEndpoint = normalizedEndpoint.substring(4);
        }
        return URI.create(normalizedBaseUrl + normalizedEndpoint);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "\n... truncated ...";
    }

    public record LlmResult(String provider, String model, String content) {
        public String providerModel() {
            return provider + "/" + model;
        }
    }

    public record EmbeddingResult(String provider, String model, int dimension, List<Double> vector) {
    }
}
