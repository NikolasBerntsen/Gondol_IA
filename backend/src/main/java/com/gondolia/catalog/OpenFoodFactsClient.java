package com.gondolia.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.gondolia.catalog.dto.BarcodeLookupResponse;
import com.gondolia.config.AppProperties;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Autocompletado de productos por código de barras contra Open Food Facts (SPEC §2.2, §6.3), para los códigos que no
 * están en el catálogo de referencia ({@link ReferenceCatalog}).
 * <p>
 * Es una ayuda para cargar un producto nuevo, nunca un requisito: si {@code app.openfoodfacts-enabled} está en
 * {@code false}, no hay internet o el servicio tarda más de 4 segundos, la respuesta es {@code found: false} y la
 * pantalla sigue funcionando con carga manual. Nunca lanza una excepción.
 */
@Slf4j
@Component
public class OpenFoodFactsClient {

    static final String BASE_URL = "https://world.openfoodfacts.org";
    static final Duration TIMEOUT = Duration.ofSeconds(4);
    private static final String USER_AGENT = "GondolIA/1.0 (prototipo universitario)";

    /** Campos que pide la API v2 (menos tráfico y respuesta más rápida). */
    private static final String FIELDS = String.join(",", "code", "product_name", "product_name_es", "generic_name",
            "generic_name_es", "brands", "quantity", "categories", "categories_tags_es", "image_front_small_url",
            "image_front_url", "image_url");

    private final RestClient restClient;
    private final boolean enabled;

    public OpenFoodFactsClient(RestClient.Builder builder, AppProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        this.restClient = builder.clone()
                .baseUrl(BASE_URL)
                .requestFactory(factory)
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
        this.enabled = properties.openfoodfactsEnabled();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Datos públicos del producto con ese código, o {@code found: false} si no se pudo obtener. */
    public BarcodeLookupResponse lookup(String barcode) {
        if (!enabled || barcode == null || barcode.isBlank()) {
            return BarcodeLookupResponse.notFound(barcode);
        }
        try {
            JsonNode body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/api/v2/product/{barcode}.json")
                            .queryParam("fields", FIELDS)
                            .build(barcode))
                    .retrieve()
                    .body(JsonNode.class);
            return parse(barcode, body);
        } catch (RuntimeException ex) {
            log.debug("Open Food Facts no respondió por {}: {}", barcode, ex.getMessage());
            return BarcodeLookupResponse.notFound(barcode);
        }
    }

    private static BarcodeLookupResponse parse(String barcode, JsonNode body) {
        JsonNode product = body == null ? null : body.path("product");
        if (product == null || product.isMissingNode() || product.isNull()) {
            return BarcodeLookupResponse.notFound(barcode);
        }
        String name = firstText(product, List.of("product_name_es", "product_name", "generic_name_es",
                "generic_name"));
        String brand = firstBrand(text(product, "brands"));
        String image = firstText(product, List.of("image_front_small_url", "image_front_url", "image_url"));
        String category = firstCategory(product);
        if (name == null && brand == null) {
            return BarcodeLookupResponse.notFound(barcode);
        }
        return new BarcodeLookupResponse(true, BarcodeLookupResponse.SOURCE_OPEN_FOOD_FACTS, barcode, name, brand,
                text(product, "quantity"), category, image);
    }

    private static String firstCategory(JsonNode product) {
        JsonNode tags = product.path("categories_tags_es");
        if (tags.isArray() && !tags.isEmpty()) {
            String tag = tags.get(tags.size() - 1).asText("");
            String clean = tag.contains(":") ? tag.substring(tag.indexOf(':') + 1) : tag;
            clean = clean.replace('-', ' ').trim();
            if (!clean.isEmpty()) {
                return capitalize(clean);
            }
        }
        String categories = text(product, "categories");
        if (categories == null) {
            return null;
        }
        String[] parts = categories.split(",");
        return capitalize(parts[parts.length - 1].trim());
    }

    private static String firstBrand(String brands) {
        if (brands == null) {
            return null;
        }
        String first = brands.split(",")[0].trim();
        return first.isEmpty() ? null : first;
    }

    private static String firstText(JsonNode node, List<String> fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            return null;
        }
        String trimmed = value.asText().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
