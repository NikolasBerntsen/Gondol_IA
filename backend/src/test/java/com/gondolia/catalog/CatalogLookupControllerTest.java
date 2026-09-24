package com.gondolia.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gondolia.catalog.dto.BarcodeLookupResponse;
import com.gondolia.config.AppProperties;
import com.gondolia.recall.RecallMatchingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Autocompletado por código de barras ({@code GET /api/tenant/catalog/lookup/{barcode}}): primero el catálogo de
 * referencia, sin tocar la red; Open Food Facts solo para los códigos que el catálogo no tiene.
 */
class CatalogLookupControllerTest {

    private static final String PLAYADITO = "7793704000911";
    private static final String UNKNOWN = "7790000000001";

    private final ReferenceCatalog referenceCatalog = new ReferenceCatalog();
    private final OpenFoodFactsClient openFoodFacts = mock(OpenFoodFactsClient.class);
    private final CatalogLookupController controller =
            new CatalogLookupController(referenceCatalog, openFoodFacts, mock(RecallMatchingService.class));

    @Test
    void knownBarcodeComesFromTheReferenceCatalogWithoutCallingOpenFoodFacts() {
        BarcodeLookupResponse response = controller.lookup(" " + PLAYADITO + " ");

        assertThat(response.found()).isTrue();
        assertThat(response.source()).isEqualTo(BarcodeLookupResponse.SOURCE_REFERENCE_CATALOG);
        assertThat(response.barcode()).isEqualTo(PLAYADITO);
        assertThat(response.name()).isEqualTo("Yerba mate Playadito 500 g");
        assertThat(response.brand()).isEqualTo("Playadito");
        assertThat(response.quantity()).isEqualTo("500 g");
        assertThat(response.categoryHint()).isEqualTo("Almacén");
        verifyNoInteractions(openFoodFacts);
    }

    @Test
    void unknownBarcodeFallsBackToOpenFoodFacts() {
        BarcodeLookupResponse fromOff = new BarcodeLookupResponse(true, BarcodeLookupResponse.SOURCE_OPEN_FOOD_FACTS,
                UNKNOWN, "Bolsa de consorcio x 10", "Genérica", null, null, null);
        when(openFoodFacts.lookup(UNKNOWN)).thenReturn(fromOff);

        assertThat(controller.lookup(UNKNOWN)).isEqualTo(fromOff);
        verify(openFoodFacts).lookup(UNKNOWN);
    }

    @Test
    void blankBarcodeIsNotFoundWithoutLookingAnywhere() {
        assertThat(controller.lookup("  ").found()).isFalse();
        verifyNoInteractions(openFoodFacts);
    }

    @Test
    void referenceCatalogStillWorksWithOpenFoodFactsDisabled() {
        OpenFoodFactsClient disabled = new OpenFoodFactsClient(RestClient.builder(), properties(false));
        CatalogLookupController offline =
                new CatalogLookupController(referenceCatalog, disabled, mock(RecallMatchingService.class));

        assertThat(disabled.isEnabled()).isFalse();
        assertThat(offline.lookup(PLAYADITO).found()).isTrue();
        assertThat(offline.lookup(PLAYADITO).source()).isEqualTo(BarcodeLookupResponse.SOURCE_REFERENCE_CATALOG);
        assertThat(offline.lookup(UNKNOWN)).isEqualTo(BarcodeLookupResponse.notFound(UNKNOWN));
    }

    private static AppProperties properties(boolean openFoodFactsEnabled) {
        return new AppProperties(new AppProperties.Jwt("x".repeat(40), 12), new AppProperties.Ai("http://localhost:1"),
                new AppProperties.Storage("./data"), "America/Argentina/Buenos_Aires", false, false,
                openFoodFactsEnabled, new AppProperties.Bootstrap(null, null), new AppProperties.Cors(List.of()));
    }
}
