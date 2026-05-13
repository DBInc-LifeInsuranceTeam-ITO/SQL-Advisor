package dbinc.sqladvisor.domain.awr.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AwrWorkerClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Value("${awr.worker.url:http://worker:8081}")
    private String workerUrl;

    public String enqueueExtraction(long reportId, String filename, String rawFilePath) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("report_id", reportId);
        body.put("filename", filename);
        body.put("raw_file_path", rawFilePath);

        JsonNode response = postJson("/jobs/extract", body);
        String jobId = response.path("job_id").asText("");
        if (jobId.isBlank()) {
            throw new IllegalStateException("worker가 job_id를 반환하지 않았습니다.");
        }
        return jobId;
    }

    private JsonNode postJson(String path, JsonNode body) {
        String baseUrl = workerUrl.endsWith("/") ? workerUrl.substring(0, workerUrl.length() - 1) : workerUrl;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("worker enqueue 실패: HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("worker enqueue 응답을 읽지 못했습니다.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("worker enqueue 요청이 중단되었습니다.", exception);
        }
    }
}
