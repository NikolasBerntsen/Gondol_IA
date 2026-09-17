package com.gondolia.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.ai.dto.AnalyzeRequest;
import com.gondolia.ai.dto.AnalyzeResponse;
import com.gondolia.ai.dto.AnalyzeSettings;
import com.gondolia.ai.dto.BarcodeValue;
import com.gondolia.ai.dto.DailySale;
import com.gondolia.ai.dto.FeedbackInput;
import com.gondolia.ai.dto.FeedbackOutcome;
import com.gondolia.ai.dto.LotInput;
import com.gondolia.ai.dto.OcrResponse;
import com.gondolia.ai.dto.ProductInput;
import com.gondolia.common.error.ApiException;
import com.gondolia.config.AppProperties;
import com.gondolia.domain.ai.RecommendationStatus;
import com.gondolia.domain.ai.RecommendationType;
import com.gondolia.domain.ai.SalesPattern;
import com.gondolia.domain.inventory.LotStatus;
import com.gondolia.domain.tenant.StockRotation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.client.RestClient;

/**
 * {@link AiClient} contra un servidor HTTP local que imita al servicio de IA: contrato JSON de §8, multipart y manejo
 * de errores.
 */
class AiClientTest {

    private static final String ANALYZE_RESPONSE = """
            {"modelVersion":"gondolia-ai-1.0","generatedAt":"2026-09-17T06:00:00Z",
             "products":[{"productId":10,"pattern":"ESTACIONAL_SEMANAL","patternDescription":"Vende 45% más los fines de semana",
               "abcClass":"A","xyzClass":"Y","avgDailySales":3.2,"trendPct":12.5,"weekdayProfile":[0.8,0.9,0.9,1.0,1.1,1.4,1.2],
               "forecastMethod":"HOLT_WINTERS","forecast":[{"date":"2026-09-18","yhat":3.4,"lo":1.2,"hi":5.6}],
               "daysOfCover":7.8,"predictedStockoutDate":"2026-09-25","reorderPoint":14,"safetyStock":5,"suggestedOrderQty":30,
               "anomalies":[{"date":"2026-09-01","quantity":20,"expected":3.1,"score":4.2,"kind":"SPIKE"}],
               "lotRisks":[{"lotId":55,"daysToExpiry":8,"quantity":12,"expectedSalesBeforeExpiry":6.1,"unitsAtRisk":6,"riskLevel":"HIGH",
                 "recommendedDiscountPct":20,"expectedUnitsSoldWithDiscount":11.2}]}],
             "recommendations":[{"type":"REORDER","productId":10,"lotId":null,"priority":85,"confidence":0.78,
               "title":"Reponer Yogur bebible frutilla 1L","explanation":"Sugerimos pedir 30 u.",
               "suggestedQuantity":30,"suggestedDiscountPct":null,"suggestedDate":"2026-09-21","expectedImpact":6300.0,"dedupeKey":"REORDER:10"}],
             "summary":{"productsAnalyzed":60,"atRiskValue":12345.0,"predictedStockouts7d":4,"anomalies30d":3,
               "patternCounts":{"ESTACIONAL_SEMANAL":10},"elasticityByCategory":{"Lácteos":2.4},"modelNotes":["ok"]}}
            """;

    private static final String OCR_RESPONSE = """
            {"text":"LOTE: L2409A VTO 25/09/2026","lines":["LOTE: L2409A","VTO 25/09/2026"],
             "expiryDates":[{"value":"2026-09-25","raw":"VTO 25/09/2026","confidence":0.92}],
             "lotNumbers":[{"value":"L2409A","raw":"LOTE: L2409A","confidence":0.85}],
             "manufactureDates":[],"barcodes":[{"value":"7791234500012","format":"EAN_13"}],
             "productNameCandidates":["SOPA DE TOMATE LA HUERTA"],"processingMs":840}
            """;

    private HttpServer server;
    private final Map<String, String> requests = new ConcurrentHashMap<>();
    private final Map<String, String> contentTypes = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> respond(exchange, 200,
                "{\"status\":\"ok\",\"version\":\"1.0.0\",\"modelVersion\":\"gondolia-ai-1.0\",\"tesseract\":true}"));
        server.createContext("/v1/analyze", exchange -> respond(exchange, 200, ANALYZE_RESPONSE));
        server.createContext("/v1/ocr", exchange -> respond(exchange, 200, OCR_RESPONSE));
        server.createContext("/v1/barcode", exchange -> respond(exchange, 422,
                "{\"code\":\"INVALID_IMAGE\",\"message\":\"No pudimos leer la imagen\"}"));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void analyzeSendsTheContractInCamelCaseAndMapsTheResponse() {
        AiClient client = client("http://127.0.0.1:" + server.getAddress().getPort());
        AnalyzeRequest request = new AnalyzeRequest(2L, 3L, "Sucursal Centro", LocalDate.parse("2026-09-17"),
                new AnalyzeSettings(StockRotation.FIFO, 15, 5, 3, 14, new BigDecimal("0.95"), 40, 14),
                List.of(new ProductInput(10L, "Yogur", "Lácteos", new BigDecimal("2100.0"), new BigDecimal("1400.0"), 10,
                        25, true, 3, LocalDate.parse("2026-03-01"),
                        List.of(new DailySale(LocalDate.parse("2026-09-16"), 4, BigDecimal.ZERO)),
                        List.of(new LotInput(55L, "L2409A", LocalDate.parse("2026-09-25"),
                                Instant.parse("2026-09-02T13:10:00Z"), 12, null, LotStatus.ACTIVE)))),
                List.of(new FeedbackInput(90L, RecommendationType.DISCOUNT, 10L, "Lácteos", RecommendationStatus.ACCEPTED,
                        new BigDecimal("20"), new FeedbackOutcome(10, 18, 1.8, null, null))));

        AnalyzeResponse response = client.analyze(request);

        String sent = requests.get("/v1/analyze");
        assertThat(contentTypes.get("/v1/analyze")).startsWith("application/json");
        assertThat(sent).contains("\"asOfDate\":\"2026-09-17\"", "\"stockRotation\":\"FIFO\"",
                "\"receivedAt\":\"2026-09-02T13:10:00Z\"", "\"dailySales\":[{\"date\":\"2026-09-16\"",
                "\"unitsBefore7d\":10", "\"branchName\":\"Sucursal Centro\"");
        assertThat(response.generatedAt()).isEqualTo(Instant.parse("2026-09-17T06:00:00Z"));
        assertThat(response.products()).singleElement().satisfies(product -> {
            assertThat(product.pattern()).isEqualTo(SalesPattern.ESTACIONAL_SEMANAL);
            assertThat(product.weekdayProfile()).hasSize(7);
            assertThat(product.forecast().getFirst().yhat()).isEqualTo(3.4);
            assertThat(product.predictedStockoutDate()).isEqualTo(LocalDate.parse("2026-09-25"));
            assertThat(product.lotRisks().getFirst().recommendedDiscountPct()).isEqualTo(20);
            assertThat(product.anomalies().getFirst().kind()).isEqualTo("SPIKE");
        });
        assertThat(response.recommendations()).singleElement().satisfies(recommendation -> {
            assertThat(recommendation.type()).isEqualTo(RecommendationType.REORDER);
            assertThat(recommendation.lotId()).isNull();
            assertThat(recommendation.dedupeKey()).isEqualTo("REORDER:10");
            assertThat(recommendation.expectedImpact()).isEqualByComparingTo("6300");
        });
        assertThat(response.summary().patternCounts()).containsEntry("ESTACIONAL_SEMANAL", 10);
        assertThat(response.summary().elasticityByCategory()).containsEntry("Lácteos", 2.4);
    }

    @Test
    void ocrUploadsTheImageAsMultipartFile() {
        AiClient client = client("http://127.0.0.1:" + server.getAddress().getPort());

        OcrResponse response = client.ocr(new byte[] {1, 2, 3}, "etiqueta.jpg", "image/jpeg");

        assertThat(contentTypes.get("/v1/ocr")).startsWith("multipart/form-data");
        assertThat(requests.get("/v1/ocr")).contains("name=\"file\"", "filename=\"etiqueta.jpg\"",
                "Content-Type: image/jpeg");
        assertThat(response.expiryDates().getFirst().value()).isEqualTo(LocalDate.parse("2026-09-25"));
        assertThat(response.lotNumbers().getFirst().value()).isEqualTo("L2409A");
        assertThat(response.barcodes()).containsExactly(new BarcodeValue("7791234500012", "EAN_13"));
        assertThat(response.manufactureDates()).isEmpty();
        assertThat(response.processingMs()).isEqualTo(840L);
        assertThat(client.isHealthy()).isTrue();
    }

    @Test
    void rejectedImagesAreBadRequestsWithTheServiceMessage() {
        AiClient client = client("http://127.0.0.1:" + server.getAddress().getPort());

        assertThatThrownBy(() -> client.barcode(new byte[] {1}, "x.png", "image/png"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getCode()).isEqualTo("INVALID_FILE");
                    assertThat(ex.getMessage()).isEqualTo("No pudimos leer la imagen");
                });
    }

    @Test
    void networkAndServerErrorsAreServiceUnavailable() {
        server.removeContext("/v1/analyze");
        server.createContext("/v1/analyze", exchange -> respond(exchange, 500, "Internal Server Error"));
        AiClient failing = client("http://127.0.0.1:" + server.getAddress().getPort());
        AiClient unreachable = client("http://127.0.0.1:1");

        for (Runnable call : List.<Runnable>of(
                () -> failing.analyze(new AnalyzeRequest(1L, 1L, "x", LocalDate.parse("2026-09-17"), null, null, null)),
                () -> unreachable.ocr(new byte[] {1}, "x.png", "image/png"))) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.getStatus().value()).isEqualTo(503);
                assertThat(ex.getCode()).isEqualTo("AI_UNAVAILABLE");
                assertThat(ex.getMessage()).isEqualTo(AiClient.MSG_UNAVAILABLE);
            });
        }
        assertThat(unreachable.isHealthy()).isFalse();
        assertThat(unreachable.health()).isEmpty();
    }

    private static AiClient client(String baseUrl) {
        AppProperties properties = new AppProperties(new AppProperties.Jwt("x".repeat(40), 12),
                new AppProperties.Ai(baseUrl), new AppProperties.Storage("./data"), "America/Argentina/Buenos_Aires",
                false, false, false, new AppProperties.Bootstrap(null, null), new AppProperties.Cors(List.of()));
        return new AiClient(RestClient.builder(), Jackson2ObjectMapperBuilder.json().build(), properties);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null) {
            contentTypes.put(path, contentType);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
