package de.firetail.compat.movebank.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

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
        String body = http.get()
                .uri(b.build().toUriString())
                .retrieve()
                .body(String.class);
        // Normalize: mirror-api returns HTTP 200 with an empty body for
        // "no match" (e.g. unknown study id). RestClient turns that into
        // a null String reference, which would NPE downstream CSV
        // parsing — collapse to an empty string so callers can assume
        // non-null and let their "empty response" branches handle it.
        return body == null ? "" : body;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * Streaming variant for tools that need to process large event-data
     * responses without loading the full CSV into a String. The consumer
     * is invoked with a Reader bound to the HTTP response body; do not
     * use the Reader after the consumer returns.
     *
     * Use for {@code entity_type=event} responses — they can be millions
     * of rows. Use {@link #directRead} for everything smaller.
     */
    public <T> T directReadStreaming(Map<String, String> params,
                                     Function<Reader, T> consumer) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/service/direct-read");
        params.forEach(b::queryParam);
        return http.get()
                .uri(b.build().toUriString())
                .exchange((req, resp) -> {
                    try (Reader r = new InputStreamReader(
                            resp.getBody(), StandardCharsets.UTF_8)) {
                        return consumer.apply(r);
                    } catch (IOException e) {
                        throw new RuntimeException(
                                "Could not read mirror-api response stream", e);
                    }
                });
    }
}
