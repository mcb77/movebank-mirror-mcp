package de.firetail.compat.movebank.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * Thin HTTP client for the running movebank-mirror-api instance.
 *
 * Single endpoint matters: {@code /service/direct-read} with the
 * standard Movebank query parameters ({@code entity_type=...},
 * optional {@code attributes=...}, etc.). Responses come back as CSV
 * which the tool implementations parse with OpenCSV.
 *
 * Base URL is configurable via {@code movebank.mirror-api.base-url}
 * (default {@code http://localhost:8080/movebank}) — see
 * {@code application.yml}.
 */
@Component
public class MirrorApiClient {

    private final RestClient http;
    private final String baseUrl;

    public MirrorApiClient(@Value("${movebank.mirror-api.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * GET {@code <base>/service/direct-read?<params>} and return the raw
     * CSV response body. Throws if the server is unreachable or returns
     * a non-2xx status — callers should catch and surface a useful
     * error to the LLM.
     */
    public String directRead(Map<String, String> params) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/service/direct-read");
        params.forEach(b::queryParam);
        return http.get()
                .uri(b.build().toUriString())
                .retrieve()
                .body(String.class);
    }

    public String baseUrl() {
        return baseUrl;
    }
}
