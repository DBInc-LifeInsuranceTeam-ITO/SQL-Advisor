package dbinc.sqladvisor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson ObjectMapper 전역 설정
 * 모든 JSON 로그를 beautify하여 가독성 향상
 */
@Configuration
public class JacksonConfig {

    /**
     * 일반 API 응답용 ObjectMapper (압축된 JSON)
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // API 응답은 압축된 형태로 유지
        return mapper;
    }

    /**
     * 로그 전용 ObjectMapper (beautified JSON)
     * 로그 파일의 가독성을 위해 들여쓰기 적용
     */
    @Bean(name = "loggingObjectMapper")
    public ObjectMapper loggingObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 로그용은 beautify 적용
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }
}