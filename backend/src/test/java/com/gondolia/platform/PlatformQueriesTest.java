package com.gondolia.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.platform.PlatformQueries.TenantFilters;
import org.junit.jupiter.api.Test;

/** Normalización del texto de búsqueda de comercios (sin base de datos). */
class PlatformQueriesTest {

    @Test
    void accentTablesLineUp() {
        assertThat(PlatformQueries.ACCENTED).hasSameSizeAs(PlatformQueries.UNACCENTED);
    }

    @Test
    void plainRemovesAccentsAndCase() {
        assertThat(PlatformQueries.plain("Almacén CÓRDOBA Ñandú Çà")).isEqualTo("almacen cordoba nandu ca");
    }

    @Test
    void likeWildcardsAreEscaped() {
        assertThat(PlatformQueries.escapeLike("50%_a\\b")).isEqualTo("50\\%\\_a\\\\b");
    }

    @Test
    void filtersNormalizeTheQuery() {
        assertThat(TenantFilters.of("  Almacén 100% ", null, null, null, null).q()).isEqualTo("almacen 100\\%");
        assertThat(TenantFilters.of("   ", null, null, null, null).q()).isNull();
    }
}
