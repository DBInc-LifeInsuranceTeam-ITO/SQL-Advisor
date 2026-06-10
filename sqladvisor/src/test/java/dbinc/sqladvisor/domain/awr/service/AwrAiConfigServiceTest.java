package dbinc.sqladvisor.domain.awr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AwrAiConfigServiceTest {

    @Test
    void syncsConfiguredEnvAiSettingsIntoRepositoryOnStartup() {
        AwrRepository repository = mock(AwrRepository.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AWR_LLM_PROVIDER", "internal")
                .withProperty("AWR_EMBEDDING_PROVIDER", "internal")
                .withProperty("INTERNAL_LLM_BASE_URL", "http://llm.example/v1/chat/completions")
                .withProperty("INTERNAL_LLM_API_KEY", "secret")
                .withProperty("INTERNAL_LLM_CHAT_MODEL", "gemma4-31b")
                .withProperty("INTERNAL_EMBEDDING_BASE_URL", "http://embedding.example/embeddings")
                .withProperty("INTERNAL_EMBEDDING_MODEL", "genai-bge-m3");

        AwrAiConfigService service = new AwrAiConfigService(repository, new ObjectMapper(), environment);
        service.syncEnvSettingsFromEnvironment();

        verify(repository).upsertAiSetting("llmProvider", "internal");
        verify(repository).upsertAiSetting("embeddingProvider", "internal");
        verify(repository).upsertAiSetting("internalBaseUrl", "http://llm.example/v1/chat/completions");
        verify(repository).upsertAiSetting("internalApiKey", "secret");
        verify(repository).upsertAiSetting("internalChatModel", "gemma4-31b");
        verify(repository).upsertAiSetting("internalEmbeddingBaseUrl", "http://embedding.example/embeddings");
        verify(repository).upsertAiSetting("internalEmbeddingModel", "genai-bge-m3");
    }

    @Test
    void ignoresBlankEnvValuesDuringStartupSync() {
        AwrRepository repository = mock(AwrRepository.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AWR_LLM_PROVIDER", "   ")
                .withProperty("INTERNAL_LLM_BASE_URL", "");

        AwrAiConfigService service = new AwrAiConfigService(repository, new ObjectMapper(), environment);
        service.syncEnvSettingsFromEnvironment();

        verifyNoInteractions(repository);
    }
}
