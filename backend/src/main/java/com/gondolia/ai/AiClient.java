package com.gondolia.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gondolia.ai.dto.AnalyzeRequest;
import com.gondolia.ai.dto.AnalyzeResponse;
import com.gondolia.ai.dto.BarcodeResponse;
import com.gondolia.ai.dto.HealthResponse;
import com.gondolia.ai.dto.OcrResponse;
import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.config.AppProperties;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cliente HTTP del servicio de IA ({@code app.ai.base-url}, SPEC §8). Timeouts: conexión 3 s; lectura 120 s para
 * {@code /v1/analyze}, 60 s para {@code /v1/ocr} y {@code /v1/barcode}, 5 s para {@code /health}.
 * <p>
 * Si el servicio no responde, falla o devuelve algo ilegible: 503 {@code AI_UNAVAILABLE} ("El servicio de IA no está
 * disponible"). Si rechaza una imagen (400/413/415/422): 400 {@code INVALID_FILE} con el mensaje del servicio.
 */
@Slf4j
@Component
public class AiClient {

    public static final String MSG_UNAVAILABLE = "El servicio de IA no está disponible";
    static final String MSG_INVALID_IMAGE = "No se pudo procesar la imagen. Probá con otra foto";

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(5);
    static final Duration ANALYZE_TIMEOUT = Duration.ofSeconds(120);
    static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(60);

    private static final Set<Integer> IMAGE_REJECTION_STATUSES = Set.of(400, 413, 415, 422);

    private final RestClient healthClient;
    private final RestClient analyzeClient;
    private final RestClient imageClient;

    public AiClient(RestClient.Builder builder, ObjectMapper objectMapper, AppProperties properties) {
        // El contrato usa fechas ISO-8601 y el servicio puede sumar campos nuevos: no depender de la config global.
        ObjectMapper mapper = objectMapper.copy()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        String baseUrl = properties.ai().baseUrl();
        this.healthClient = build(builder, mapper, baseUrl, HEALTH_TIMEOUT);
        this.analyzeClient = build(builder, mapper, baseUrl, ANALYZE_TIMEOUT);
        this.imageClient = build(builder, mapper, baseUrl, IMAGE_TIMEOUT);
    }

    /** {@code true} si {@code GET /health} responde {@code status: "ok"}. Nunca lanza excepción. */
    public boolean isHealthy() {
        return health().map(response -> "ok".equalsIgnoreCase(response.status())).orElse(false);
    }

    /** Estado del servicio, o vacío si no responde. */
    public Optional<HealthResponse> health() {
        try {
            return Optional.ofNullable(healthClient.get().uri("/health").retrieve().body(HealthResponse.class));
        } catch (RuntimeException ex) {
            log.debug("El servicio de IA no respondió /health: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Análisis de patrones y recomendaciones de una sucursal ({@code POST /v1/analyze}). */
    public AnalyzeResponse analyze(AnalyzeRequest request) {
        return call("analyze", false, () -> analyzeClient.post()
                .uri("/v1/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(AnalyzeResponse.class));
    }

    /** Lectura de etiqueta: vencimientos, lotes, códigos y nombres candidatos ({@code POST /v1/ocr}). */
    public OcrResponse ocr(byte[] bytes, String filename, String contentType) {
        return call("ocr", true, () -> imageClient.post()
                .uri("/v1/ocr")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart(bytes, filename, contentType))
                .retrieve()
                .body(OcrResponse.class));
    }

    /** Lectura de códigos de barras en una foto ({@code POST /v1/barcode}). */
    public BarcodeResponse barcode(byte[] bytes, String filename, String contentType) {
        return call("barcode", true, () -> imageClient.post()
                .uri("/v1/barcode")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart(bytes, filename, contentType))
                .retrieve()
                .body(BarcodeResponse.class));
    }

    private <T> T call(String operation, boolean imageRequest, Supplier<T> request) {
        try {
            T body = request.get();
            if (body == null) {
                log.warn("El servicio de IA devolvió una respuesta vacía en {}", operation);
                throw unavailable();
            }
            return body;
        } catch (HttpClientErrorException ex) {
            if (imageRequest && IMAGE_REJECTION_STATUSES.contains(ex.getStatusCode().value())) {
                throw new BadRequestException(ErrorCodes.INVALID_FILE, serviceMessage(ex).orElse(MSG_INVALID_IMAGE));
            }
            log.warn("El servicio de IA rechazó {}: {} {}", operation, ex.getStatusCode().value(),
                    abbreviate(ex.getResponseBodyAsString()));
            throw unavailable();
        } catch (RestClientException ex) {
            log.warn("El servicio de IA no está disponible ({}): {}", operation, ex.getMessage());
            throw unavailable();
        }
    }

    private static MultiValueMap<String, Object> multipart(byte[] bytes, String filename, String contentType) {
        String name = filename == null || filename.isBlank() ? "imagen" : filename;
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(parseMediaType(contentType));
        partHeaders.setContentDispositionFormData("file", name);
        ByteArrayResource resource = new ByteArrayResource(bytes == null ? new byte[0] : bytes) {
            @Override
            public String getFilename() {
                return name;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, partHeaders));
        return body;
    }

    private static MediaType parseMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<String> serviceMessage(HttpClientErrorException ex) {
        try {
            Map<String, Object> body = ex.getResponseBodyAs(Map.class);
            Object message = body == null ? null : body.get("message");
            return message instanceof String text && !text.isBlank() ? Optional.of(text) : Optional.empty();
        } catch (RuntimeException parseError) {
            return Optional.empty();
        }
    }

    private static RestClient build(RestClient.Builder builder, ObjectMapper mapper, String baseUrl,
                                    Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(readTimeout);
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .messageConverters(converters -> converters.replaceAll(converter ->
                        converter instanceof MappingJackson2HttpMessageConverter
                                ? new MappingJackson2HttpMessageConverter(mapper)
                                : converter))
                .build();
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.AI_UNAVAILABLE, MSG_UNAVAILABLE);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
