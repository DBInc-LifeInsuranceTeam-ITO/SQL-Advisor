package dbinc.sqladvisor.domain.awr.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dbinc.sqladvisor.domain.awr.dto.AwrDtos;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AwrAiConfigService {

    private static final Set<String> LLM_PROVIDERS = Set.of("local", "openai", "gemini", "internal", "ollama");
    private static final Set<String> EMBEDDING_PROVIDERS = Set.of("none", "openai", "gemini", "internal", "ollama");
    private static final String KEY_LLM_PROVIDER = "llmProvider";
    private static final String KEY_EMBEDDING_PROVIDER = "embeddingProvider";
    private static final String KEY_OPENAI_API_KEY = "openaiApiKey";
    private static final String KEY_OPENAI_CHAT_MODEL = "openaiChatModel";
    private static final String KEY_OPENAI_EMBEDDING_MODEL = "openaiEmbeddingModel";
    private static final String KEY_GEMINI_API_KEY = "geminiApiKey";
    private static final String KEY_GEMINI_CHAT_MODEL = "geminiChatModel";
    private static final String KEY_GEMINI_EMBEDDING_MODEL = "geminiEmbeddingModel";
    private static final String KEY_INTERNAL_API_KEY = "internalApiKey";
    private static final String KEY_INTERNAL_BASE_URL = "internalBaseUrl";
    private static final String KEY_INTERNAL_CHAT_MODEL = "internalChatModel";
    private static final String KEY_INTERNAL_EMBEDDING_BASE_URL = "internalEmbeddingBaseUrl";
    private static final String KEY_INTERNAL_EMBEDDING_MODEL = "internalEmbeddingModel";
    private static final String KEY_OLLAMA_BASE_URL = "ollamaBaseUrl";
    private static final String KEY_OLLAMA_CHAT_MODEL = "ollamaChatModel";
    private static final String KEY_OLLAMA_EMBEDDING_MODEL = "ollamaEmbeddingModel";

    private final AwrRepository repository;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${awr.ai.llm-provider:local}")
    private String llmProvider;

    @Value("${awr.ai.embedding-provider:none}")
    private String embeddingProvider;

    @Value("${awr.ai.openai-api-key:}")
    private String openaiApiKey;

    @Value("${awr.ai.openai-chat-model:gpt-4.1-mini}")
    private String openaiChatModel;

    @Value("${awr.ai.openai-embedding-model:text-embedding-3-small}")
    private String openaiEmbeddingModel;

    @Value("${awr.ai.gemini-api-key:}")
    private String geminiApiKey;

    @Value("${awr.ai.gemini-chat-model:gemini-3.1-flash-lite}")
    private String geminiChatModel;

    @Value("${awr.ai.gemini-embedding-model:gemini-embedding-001}")
    private String geminiEmbeddingModel;

    @Value("${awr.ai.internal-api-key:}")
    private String internalApiKey;

    @Value("${awr.ai.internal-base-url:}")
    private String internalBaseUrl;

    @Value("${awr.ai.internal-chat-model:gemma4-31b}")
    private String internalChatModel;

    @Value("${awr.ai.internal-embedding-base-url:}")
    private String internalEmbeddingBaseUrl;

    @Value("${awr.ai.internal-embedding-model:genai-bge-m3}")
    private String internalEmbeddingModel;

    @Value("${awr.ai.ollama-base-url:http://host.docker.internal:11434}")
    private String ollamaBaseUrl;

    @Value("${awr.ai.ollama-chat-model:llama3.1}")
    private String ollamaChatModel;

    @Value("${awr.ai.ollama-embedding-model:embeddinggemma}")
    private String ollamaEmbeddingModel;

    @Value("${awr.ai.anthropic-api-key:}")
    private String anthropicApiKey;

    @Value("${awr.ai.anthropic-chat-model:claude-3-5-sonnet-latest}")
    private String anthropicChatModel;

    @Value("${awr.ai.xai-api-key:}")
    private String xaiApiKey;

    @Value("${awr.ai.xai-chat-model:grok-2-latest}")
    private String xaiChatModel;

    @Value("${awr.ai.cohere-api-key:}")
    private String cohereApiKey;

    @Value("${awr.embedding.dimension:1536}")
    private int embeddingDimension;

    @PostConstruct
    void syncEnvSettingsFromEnvironment() {
        syncSupportedEnvSetting(KEY_LLM_PROVIDER, "AWR_LLM_PROVIDER", LLM_PROVIDERS, "local", "LLM provider");
        syncSupportedEnvSetting(KEY_EMBEDDING_PROVIDER, "AWR_EMBEDDING_PROVIDER", EMBEDDING_PROVIDERS, "none", "Embedding provider");
        syncEnvSetting(KEY_OPENAI_API_KEY, "OPENAI_API_KEY");
        syncEnvSetting(KEY_OPENAI_CHAT_MODEL, "OPENAI_CHAT_MODEL");
        syncEnvSetting(KEY_OPENAI_EMBEDDING_MODEL, "OPENAI_EMBEDDING_MODEL");
        syncEnvSetting(KEY_GEMINI_API_KEY, "GEMINI_API_KEY");
        syncEnvSetting(KEY_GEMINI_CHAT_MODEL, "GEMINI_CHAT_MODEL");
        syncEnvSetting(KEY_GEMINI_EMBEDDING_MODEL, "GEMINI_EMBEDDING_MODEL");
        syncEnvSetting(KEY_INTERNAL_API_KEY, "INTERNAL_LLM_API_KEY");
        syncEnvSetting(KEY_INTERNAL_BASE_URL, "INTERNAL_LLM_BASE_URL");
        syncEnvSetting(KEY_INTERNAL_CHAT_MODEL, "INTERNAL_LLM_CHAT_MODEL");
        syncEnvSetting(KEY_INTERNAL_EMBEDDING_BASE_URL, "INTERNAL_EMBEDDING_BASE_URL");
        syncEnvSetting(KEY_INTERNAL_EMBEDDING_MODEL, "INTERNAL_EMBEDDING_MODEL");
        syncEnvSetting(KEY_OLLAMA_BASE_URL, "OLLAMA_BASE_URL");
        syncEnvSetting(KEY_OLLAMA_CHAT_MODEL, "OLLAMA_CHAT_MODEL");
        syncEnvSetting(KEY_OLLAMA_EMBEDDING_MODEL, "OLLAMA_EMBEDDING_MODEL");
    }

    public AwrDtos.AiConfigResponse getConfig() {
        Map<String, String> saved = repository.findAiSettings();
        ActiveAiSettings settings = activeSettings();

        List<String> configuredProviders = new ArrayList<>();
        addIfConfigured(configuredProviders, "openai", settings.openaiApiKey());
        addIfConfigured(configuredProviders, "gemini", settings.geminiApiKey());
        if (hasText(settings.internalBaseUrl()) || hasText(settings.internalEmbeddingBaseUrl())) {
            configuredProviders.add("internal");
        }
        if ("ollama".equals(settings.llmProvider()) || "ollama".equals(settings.embeddingProvider())) {
            configuredProviders.add("ollama");
        }
        addIfConfigured(configuredProviders, "anthropic", anthropicApiKey);
        addIfConfigured(configuredProviders, "xai", xaiApiKey);
        addIfConfigured(configuredProviders, "cohere-rerank", cohereApiKey);

        boolean externalLlmEnabled = !"local".equals(settings.llmProvider());
        boolean llmKeyConfigured = !externalLlmEnabled || hasLlmConfigForProvider(settings, settings.llmProvider());
        boolean embeddingKeyConfigured = "none".equals(settings.embeddingProvider()) || hasEmbeddingConfigForProvider(settings, settings.embeddingProvider());

        List<String> missing = new ArrayList<>();
        if (externalLlmEnabled && !llmKeyConfigured) {
            missing.add(keyNameForLlmProvider(settings.llmProvider()));
        }
        if (!"none".equals(settings.embeddingProvider()) && !embeddingKeyConfigured) {
            missing.add(keyNameForEmbeddingProvider(settings.embeddingProvider()));
        }

        return new AwrDtos.AiConfigResponse(
                settings.llmProvider(),
                settings.embeddingProvider(),
                chatModelFor(settings, settings.llmProvider()),
                embeddingModelFor(settings, settings.embeddingProvider()),
                settings.openaiChatModel(),
                settings.openaiEmbeddingModel(),
                settings.geminiChatModel(),
                settings.geminiEmbeddingModel(),
                settings.internalBaseUrl(),
                settings.internalChatModel(),
                settings.internalEmbeddingBaseUrl(),
                settings.internalEmbeddingModel(),
                settings.ollamaBaseUrl(),
                settings.ollamaChatModel(),
                settings.ollamaEmbeddingModel(),
                externalLlmEnabled,
                llmKeyConfigured,
                embeddingKeyConfigured,
                configuredProviders,
                missing,
                settingSources(saved),
                providerConfigs(saved, settings)
        );
    }

    public AwrDtos.AiConfigResponse updateConfig(AwrDtos.AiConfigUpdateRequest request) {
        if (request == null) {
            return getConfig();
        }

        if (hasText(request.llmProvider())) {
            repository.upsertAiSetting(KEY_LLM_PROVIDER, normalizeSupported(request.llmProvider(), LLM_PROVIDERS, "local", "LLM provider"));
        }
        if (hasText(request.embeddingProvider())) {
            repository.upsertAiSetting(KEY_EMBEDDING_PROVIDER, normalizeSupported(request.embeddingProvider(), EMBEDDING_PROVIDERS, "none", "Embedding provider"));
        }

        upsertTextSetting(KEY_OPENAI_CHAT_MODEL, request.openaiChatModel());
        upsertTextSetting(KEY_OPENAI_EMBEDDING_MODEL, request.openaiEmbeddingModel());
        upsertTextSetting(KEY_GEMINI_CHAT_MODEL, request.geminiChatModel());
        upsertTextSetting(KEY_GEMINI_EMBEDDING_MODEL, request.geminiEmbeddingModel());
        upsertTextSetting(KEY_INTERNAL_BASE_URL, request.internalBaseUrl());
        upsertTextSetting(KEY_INTERNAL_CHAT_MODEL, request.internalChatModel());
        upsertTextSetting(KEY_INTERNAL_EMBEDDING_BASE_URL, request.internalEmbeddingBaseUrl());
        upsertTextSetting(KEY_INTERNAL_EMBEDDING_MODEL, request.internalEmbeddingModel());
        upsertTextSetting(KEY_OLLAMA_BASE_URL, request.ollamaBaseUrl());
        upsertTextSetting(KEY_OLLAMA_CHAT_MODEL, request.ollamaChatModel());
        upsertTextSetting(KEY_OLLAMA_EMBEDDING_MODEL, request.ollamaEmbeddingModel());

        if (Boolean.TRUE.equals(request.clearOpenaiApiKey())) {
            repository.deleteAiSetting(KEY_OPENAI_API_KEY);
        } else {
            upsertSecretSetting(KEY_OPENAI_API_KEY, request.openaiApiKey());
        }

        if (Boolean.TRUE.equals(request.clearGeminiApiKey())) {
            repository.deleteAiSetting(KEY_GEMINI_API_KEY);
        } else {
            upsertSecretSetting(KEY_GEMINI_API_KEY, request.geminiApiKey());
        }

        if (Boolean.TRUE.equals(request.clearInternalApiKey())) {
            repository.deleteAiSetting(KEY_INTERNAL_API_KEY);
        } else {
            upsertSecretSetting(KEY_INTERNAL_API_KEY, request.internalApiKey());
        }

        return getConfig();
    }

    public AwrDtos.AiModelOptionsResponse getModelOptions() {
        ActiveAiSettings settings = activeSettings();
        List<String> warnings = new ArrayList<>();

        List<String> openaiChatModels = providerModels(
                List.of(settings.openaiChatModel(), "gpt-4.1-mini", "gpt-4.1", "gpt-4o-mini", "gpt-4o"),
                () -> openaiModels(settings.openaiApiKey(), false),
                "OpenAI 모델 목록 조회 실패",
                warnings
        );
        List<String> openaiEmbeddingModels = providerModels(
                List.of(settings.openaiEmbeddingModel(), "text-embedding-3-small", "text-embedding-3-large"),
                () -> openaiModels(settings.openaiApiKey(), true),
                "OpenAI embedding 모델 목록 조회 실패",
                warnings
        );
        List<String> geminiChatModels = providerModels(
                List.of(settings.geminiChatModel(), "gemini-3.1-flash-lite", "gemini-2.5-flash", "gemini-2.0-flash-lite"),
                () -> geminiModels(settings.geminiApiKey(), "generateContent"),
                "Gemini 모델 목록 조회 실패",
                warnings
        );
        List<String> geminiEmbeddingModels = providerModels(
                List.of(settings.geminiEmbeddingModel(), "gemini-embedding-001", "text-embedding-004"),
                () -> geminiModels(settings.geminiApiKey(), "embedContent"),
                "Gemini embedding 모델 목록 조회 실패",
                warnings
        );
        List<String> internalChatModels = providerModels(
                List.of(settings.internalChatModel(), "gemma4-31b"),
                () -> internalModels(settings.internalBaseUrl(), settings.internalApiKey()),
                "내부 모델 목록 조회 실패",
                warnings
        );
        List<String> internalEmbeddingModels = providerModels(
                List.of(settings.internalEmbeddingModel(), "genai-bge-m3"),
                () -> List.of(),
                "Internal embedding model list lookup failed",
                warnings
        );
        List<String> ollamaModels = providerModels(
                List.of(settings.ollamaChatModel(), settings.ollamaEmbeddingModel(), "llama3.1", "embeddinggemma", "nomic-embed-text"),
                () -> ollamaModels(settings.ollamaBaseUrl()),
                "Ollama 모델 목록 조회 실패",
                warnings
        );

        return new AwrDtos.AiModelOptionsResponse(
                openaiChatModels,
                openaiEmbeddingModels,
                geminiChatModels,
                geminiEmbeddingModels,
                internalChatModels,
                internalEmbeddingModels,
                withDefaults(ollamaModels, List.of(settings.ollamaChatModel())),
                withDefaults(ollamaModels, List.of(settings.ollamaEmbeddingModel())),
                warnings
        );
    }

    public ActiveAiSettings activeSettings() {
        Map<String, String> saved = repository.findAiSettings();
        return new ActiveAiSettings(
                normalizeSupported(value(saved, KEY_LLM_PROVIDER, llmProvider), LLM_PROVIDERS, "local", "LLM provider"),
                normalizeSupported(value(saved, KEY_EMBEDDING_PROVIDER, embeddingProvider), EMBEDDING_PROVIDERS, "none", "Embedding provider"),
                value(saved, KEY_OPENAI_API_KEY, openaiApiKey),
                value(saved, KEY_OPENAI_CHAT_MODEL, openaiChatModel),
                value(saved, KEY_OPENAI_EMBEDDING_MODEL, openaiEmbeddingModel),
                value(saved, KEY_GEMINI_API_KEY, geminiApiKey),
                value(saved, KEY_GEMINI_CHAT_MODEL, geminiChatModel),
                value(saved, KEY_GEMINI_EMBEDDING_MODEL, geminiEmbeddingModel),
                value(saved, KEY_INTERNAL_API_KEY, internalApiKey),
                value(saved, KEY_INTERNAL_BASE_URL, internalBaseUrl),
                value(saved, KEY_INTERNAL_CHAT_MODEL, internalChatModel),
                value(saved, KEY_INTERNAL_EMBEDDING_BASE_URL, internalEmbeddingBaseUrl),
                value(saved, KEY_INTERNAL_EMBEDDING_MODEL, internalEmbeddingModel),
                value(saved, KEY_OLLAMA_BASE_URL, ollamaBaseUrl),
                value(saved, KEY_OLLAMA_CHAT_MODEL, ollamaChatModel),
                value(saved, KEY_OLLAMA_EMBEDDING_MODEL, ollamaEmbeddingModel),
                embeddingDimension
        );
    }

    private Map<String, String> settingSources(Map<String, String> saved) {
        return Map.ofEntries(
                Map.entry(KEY_LLM_PROVIDER, source(saved, KEY_LLM_PROVIDER, llmProvider)),
                Map.entry(KEY_EMBEDDING_PROVIDER, source(saved, KEY_EMBEDDING_PROVIDER, embeddingProvider)),
                Map.entry(KEY_OPENAI_API_KEY, source(saved, KEY_OPENAI_API_KEY, openaiApiKey)),
                Map.entry(KEY_OPENAI_CHAT_MODEL, source(saved, KEY_OPENAI_CHAT_MODEL, openaiChatModel)),
                Map.entry(KEY_OPENAI_EMBEDDING_MODEL, source(saved, KEY_OPENAI_EMBEDDING_MODEL, openaiEmbeddingModel)),
                Map.entry(KEY_GEMINI_API_KEY, source(saved, KEY_GEMINI_API_KEY, geminiApiKey)),
                Map.entry(KEY_GEMINI_CHAT_MODEL, source(saved, KEY_GEMINI_CHAT_MODEL, geminiChatModel)),
                Map.entry(KEY_GEMINI_EMBEDDING_MODEL, source(saved, KEY_GEMINI_EMBEDDING_MODEL, geminiEmbeddingModel)),
                Map.entry(KEY_INTERNAL_API_KEY, source(saved, KEY_INTERNAL_API_KEY, internalApiKey)),
                Map.entry(KEY_INTERNAL_BASE_URL, source(saved, KEY_INTERNAL_BASE_URL, internalBaseUrl)),
                Map.entry(KEY_INTERNAL_CHAT_MODEL, source(saved, KEY_INTERNAL_CHAT_MODEL, internalChatModel)),
                Map.entry(KEY_INTERNAL_EMBEDDING_BASE_URL, source(saved, KEY_INTERNAL_EMBEDDING_BASE_URL, internalEmbeddingBaseUrl)),
                Map.entry(KEY_INTERNAL_EMBEDDING_MODEL, source(saved, KEY_INTERNAL_EMBEDDING_MODEL, internalEmbeddingModel)),
                Map.entry(KEY_OLLAMA_BASE_URL, source(saved, KEY_OLLAMA_BASE_URL, ollamaBaseUrl)),
                Map.entry(KEY_OLLAMA_CHAT_MODEL, source(saved, KEY_OLLAMA_CHAT_MODEL, ollamaChatModel)),
                Map.entry(KEY_OLLAMA_EMBEDDING_MODEL, source(saved, KEY_OLLAMA_EMBEDDING_MODEL, ollamaEmbeddingModel))
        );
    }

    private List<AwrDtos.AiProviderConfigResponse> providerConfigs(Map<String, String> saved, ActiveAiSettings settings) {
        return List.of(
                new AwrDtos.AiProviderConfigResponse(
                        "local",
                        "Local",
                        "local".equals(settings.llmProvider()),
                        "none".equals(settings.embeddingProvider()),
                        true,
                        "builtin",
                        "rule-based-local-advisor",
                        "builtin",
                        "none",
                        "builtin",
                        "",
                        "none"
                ),
                new AwrDtos.AiProviderConfigResponse(
                        "openai",
                        "OpenAI",
                        "openai".equals(settings.llmProvider()),
                        "openai".equals(settings.embeddingProvider()),
                        hasText(settings.openaiApiKey()),
                        source(saved, KEY_OPENAI_API_KEY, openaiApiKey),
                        settings.openaiChatModel(),
                        source(saved, KEY_OPENAI_CHAT_MODEL, openaiChatModel),
                        settings.openaiEmbeddingModel(),
                        source(saved, KEY_OPENAI_EMBEDDING_MODEL, openaiEmbeddingModel),
                        "",
                        "none"
                ),
                new AwrDtos.AiProviderConfigResponse(
                        "gemini",
                        "Gemini",
                        "gemini".equals(settings.llmProvider()),
                        "gemini".equals(settings.embeddingProvider()),
                        hasText(settings.geminiApiKey()),
                        source(saved, KEY_GEMINI_API_KEY, geminiApiKey),
                        settings.geminiChatModel(),
                        source(saved, KEY_GEMINI_CHAT_MODEL, geminiChatModel),
                        settings.geminiEmbeddingModel(),
                        source(saved, KEY_GEMINI_EMBEDDING_MODEL, geminiEmbeddingModel),
                        "",
                        "none"
                ),
                new AwrDtos.AiProviderConfigResponse(
                        "internal",
                        "Internal",
                        "internal".equals(settings.llmProvider()),
                        "internal".equals(settings.embeddingProvider()),
                        hasText(settings.internalApiKey()),
                        source(saved, KEY_INTERNAL_API_KEY, internalApiKey),
                        settings.internalChatModel(),
                        source(saved, KEY_INTERNAL_CHAT_MODEL, internalChatModel),
                        settings.internalEmbeddingModel(),
                        source(saved, KEY_INTERNAL_EMBEDDING_MODEL, internalEmbeddingModel),
                        settings.internalBaseUrl(),
                        source(saved, KEY_INTERNAL_BASE_URL, internalBaseUrl)
                ),
                new AwrDtos.AiProviderConfigResponse(
                        "ollama",
                        "Ollama",
                        "ollama".equals(settings.llmProvider()),
                        "ollama".equals(settings.embeddingProvider()),
                        hasText(settings.ollamaBaseUrl()),
                        source(saved, KEY_OLLAMA_BASE_URL, ollamaBaseUrl),
                        settings.ollamaChatModel(),
                        source(saved, KEY_OLLAMA_CHAT_MODEL, ollamaChatModel),
                        settings.ollamaEmbeddingModel(),
                        source(saved, KEY_OLLAMA_EMBEDDING_MODEL, ollamaEmbeddingModel),
                        settings.ollamaBaseUrl(),
                        source(saved, KEY_OLLAMA_BASE_URL, ollamaBaseUrl)
                )
        );
    }

    private boolean hasLlmConfigForProvider(ActiveAiSettings settings, String provider) {
        return switch (provider) {
            case "openai" -> hasText(settings.openaiApiKey());
            case "gemini" -> hasText(settings.geminiApiKey());
            case "internal" -> hasText(settings.internalBaseUrl()) && hasText(settings.internalApiKey()) && hasText(settings.internalChatModel());
            case "ollama" -> hasText(settings.ollamaBaseUrl()) && hasText(settings.ollamaChatModel());
            case "anthropic", "claude" -> hasText(anthropicApiKey);
            case "xai", "grok" -> hasText(xaiApiKey);
            case "cohere" -> hasText(cohereApiKey);
            case "local", "none" -> true;
            default -> false;
        };
    }

    private boolean hasEmbeddingConfigForProvider(ActiveAiSettings settings, String provider) {
        return switch (provider) {
            case "openai" -> hasText(settings.openaiApiKey());
            case "gemini" -> hasText(settings.geminiApiKey());
            case "internal" -> hasText(settings.internalEmbeddingBaseUrl()) && hasText(settings.internalEmbeddingModel());
            case "ollama" -> hasText(settings.ollamaBaseUrl()) && hasText(settings.ollamaEmbeddingModel());
            case "local", "none" -> true;
            default -> false;
        };
    }

    private String chatModelFor(ActiveAiSettings settings, String provider) {
        return switch (provider) {
            case "openai" -> settings.openaiChatModel();
            case "gemini" -> settings.geminiChatModel();
            case "internal" -> settings.internalChatModel();
            case "ollama" -> settings.ollamaChatModel();
            case "anthropic", "claude" -> anthropicChatModel;
            case "xai", "grok" -> xaiChatModel;
            default -> "rule-based-local-advisor";
        };
    }

    private String embeddingModelFor(ActiveAiSettings settings, String provider) {
        return switch (provider) {
            case "openai" -> settings.openaiEmbeddingModel();
            case "gemini" -> settings.geminiEmbeddingModel();
            case "internal" -> settings.internalEmbeddingModel();
            case "ollama" -> settings.ollamaEmbeddingModel();
            default -> "none";
        };
    }

    private String keyNameForLlmProvider(String provider) {
        return switch (provider) {
            case "openai" -> "OPENAI_API_KEY";
            case "gemini" -> "GEMINI_API_KEY";
            case "internal" -> "INTERNAL_LLM_BASE_URL/INTERNAL_LLM_API_KEY";
            case "ollama" -> "OLLAMA_BASE_URL";
            case "anthropic", "claude" -> "ANTHROPIC_API_KEY";
            case "xai", "grok" -> "XAI_API_KEY";
            case "cohere" -> "COHERE_API_KEY";
            default -> provider.toUpperCase(Locale.ROOT) + "_API_KEY";
        };
    }

    private String keyNameForEmbeddingProvider(String provider) {
        return switch (provider) {
            case "openai" -> "OPENAI_API_KEY";
            case "gemini" -> "GEMINI_API_KEY";
            case "internal" -> "INTERNAL_EMBEDDING_BASE_URL/INTERNAL_EMBEDDING_MODEL";
            case "ollama" -> "OLLAMA_BASE_URL";
            default -> provider.toUpperCase(Locale.ROOT) + "_API_KEY";
        };
    }

    private void addIfConfigured(List<String> providers, String provider, String key) {
        if (hasText(key)) {
            providers.add(provider);
        }
    }

    private void upsertTextSetting(String key, String value) {
        if (hasText(value)) {
            repository.upsertAiSetting(key, value.trim());
        }
    }

    private void upsertSecretSetting(String key, String value) {
        if (hasText(value)) {
            repository.upsertAiSetting(key, value.trim());
        }
    }

    private void syncSupportedEnvSetting(String key, String envName, Set<String> supported, String fallback, String label) {
        String value = environment.getProperty(envName);
        if (hasText(value)) {
            repository.upsertAiSetting(key, normalizeSupported(value, supported, fallback, label));
        }
    }

    private void syncEnvSetting(String key, String envName) {
        String value = environment.getProperty(envName);
        if (hasText(value)) {
            repository.upsertAiSetting(key, value.trim());
        }
    }

    private List<String> providerModels(List<String> defaults,
                                        ProviderModelSupplier supplier,
                                        String warningPrefix,
                                        List<String> warnings) {
        List<String> options = new ArrayList<>(defaults);
        try {
            options.addAll(supplier.get());
        } catch (RuntimeException exception) {
            warnings.add(warningPrefix + ": " + exception.getMessage());
        }
        return withDefaults(options, List.of());
    }

    private List<String> openaiModels(String apiKey, boolean embeddingOnly) {
        if (!hasText(apiKey)) {
            return List.of();
        }
        JsonNode response = getJson(
                URI.create("https://api.openai.com/v1/models"),
                "Bearer " + apiKey
        );
        List<String> models = new ArrayList<>();
        for (JsonNode model : response.path("data")) {
            String id = model.path("id").asText("");
            if (!hasText(id)) {
                continue;
            }
            boolean embeddingModel = id.toLowerCase(Locale.ROOT).contains("embedding");
            if (embeddingOnly == embeddingModel) {
                models.add(id);
            }
        }
        return models;
    }

    private List<String> geminiModels(String apiKey, String generationMethod) {
        if (!hasText(apiKey)) {
            return List.of();
        }
        JsonNode response = getJson(
                URI.create("https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey),
                null
        );
        List<String> models = new ArrayList<>();
        for (JsonNode model : response.path("models")) {
            if (!supportsMethod(model, generationMethod)) {
                continue;
            }
            String name = model.path("name").asText("");
            if (hasText(name)) {
                models.add(name.startsWith("models/") ? name.substring("models/".length()) : name);
            }
        }
        return models;
    }

    private List<String> ollamaModels(String baseUrl) {
        if (!hasText(baseUrl)) {
            return List.of();
        }
        JsonNode response = getJson(ollamaUri(baseUrl, "/api/tags"), null);
        List<String> models = new ArrayList<>();
        for (JsonNode model : response.path("models")) {
            String name = model.path("name").asText("");
            if (hasText(name)) {
                models.add(name);
            }
        }
        return models;
    }

    private List<String> internalModels(String baseUrl, String apiKey) {
        if (!hasText(baseUrl)) {
            return List.of();
        }
        JsonNode response = getJson(internalUri(baseUrl, "/v1/models"), hasText(apiKey) ? "Bearer " + apiKey : null);
        List<String> models = new ArrayList<>();
        for (JsonNode model : response.path("data")) {
            String id = model.path("id").asText("");
            if (hasText(id)) {
                models.add(id);
            }
        }
        return models;
    }

    private boolean supportsMethod(JsonNode model, String method) {
        for (JsonNode supportedMethod : model.path("supportedGenerationMethods")) {
            if (method.equals(supportedMethod.asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode getJson(URI uri, String authorizationHeader) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(4))
                    .GET();
            if (authorizationHeader != null) {
                requestBuilder.header("Authorization", authorizationHeader);
            }
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("응답을 읽지 못했습니다.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("요청이 중단되었습니다.");
        }
    }

    private URI ollamaUri(String baseUrl, String endpoint) {
        String normalizedBaseUrl = baseUrl.trim().replaceAll("/+$", "");
        String normalizedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        if (normalizedBaseUrl.endsWith("/api") && normalizedEndpoint.startsWith("/api/")) {
            normalizedEndpoint = normalizedEndpoint.substring(4);
        }
        return URI.create(normalizedBaseUrl + normalizedEndpoint);
    }

    private URI internalUri(String baseUrl, String endpoint) {
        String normalizedBaseUrl = baseUrl.trim().replaceAll("/+$", "");
        if (normalizedBaseUrl.endsWith("/chat/completions")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - "/chat/completions".length());
        }
        String normalizedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        if (normalizedBaseUrl.endsWith("/v1") && normalizedEndpoint.startsWith("/v1/")) {
            normalizedEndpoint = normalizedEndpoint.substring(3);
        }
        return URI.create(normalizedBaseUrl + normalizedEndpoint);
    }

    private List<String> withDefaults(List<String> values, List<String> extraDefaults) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (hasText(value)) {
                unique.add(value.trim());
            }
        }
        for (String value : extraDefaults) {
            if (hasText(value)) {
                unique.add(value.trim());
            }
        }
        return unique.stream()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private String value(Map<String, String> saved, String key, String fallback) {
        String value = saved.get(key);
        return hasText(value) ? value.trim() : (fallback == null ? "" : fallback.trim());
    }

    private String source(Map<String, String> saved, String key, String fallback) {
        String value = saved.get(key);
        if (hasText(value) && (!hasText(fallback) || !value.trim().equals(fallback.trim()))) {
            return "web";
        }
        if (hasText(fallback)) {
            return "env";
        }
        return "none";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeSupported(String value, Set<String> supported, String fallback, String label) {
        if (!hasText(value)) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!supported.contains(normalized)) {
            throw new IllegalArgumentException(label + "는 " + String.join(", ", supported) + " 중 하나여야 합니다.");
        }
        return normalized;
    }

    public record ActiveAiSettings(
            String llmProvider,
            String embeddingProvider,
            String openaiApiKey,
            String openaiChatModel,
            String openaiEmbeddingModel,
            String geminiApiKey,
            String geminiChatModel,
            String geminiEmbeddingModel,
            String internalApiKey,
            String internalBaseUrl,
            String internalChatModel,
            String internalEmbeddingBaseUrl,
            String internalEmbeddingModel,
            String ollamaBaseUrl,
            String ollamaChatModel,
            String ollamaEmbeddingModel,
            int embeddingDimension
    ) {
    }

    @FunctionalInterface
    private interface ProviderModelSupplier {
        List<String> get();
    }
}
