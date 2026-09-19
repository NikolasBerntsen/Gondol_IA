package com.gondolia.seed;

import com.gondolia.domain.inventory.ProductUnit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catálogo maestro de los datos demo: productos con marcas ficticias y EAN-13 válidos ({@code 779…}), agrupados
 * por rubro, más los proveedores. Cada comercio toma los productos de su rubro ({@link #forTag(char)}).
 * <p>
 * Formato de cada línea: {@code clave|categoría|nombre|marca|empresa|artículo|unidad|costo|precio|mínimo|vida útil
 * (días, 0 = sin vencimiento)|patrón|ventas diarias base|múltiplo de pedido|proveedor|rubros}. Rubros: A almacén,
 * M minimercado, D dietética, K kiosco, F farmacia.
 */
final class DemoCatalog {

    /** Patrón de demanda simulado (SPEC §11). */
    enum Pattern {
        /** Estable (leche, pan): casi sin variación. */
        STABLE,
        /** Semanal suave. */
        MILD,
        /** Finde fuerte (bebidas, snacks). */
        WEEKEND,
        /** Intermitente (especias): la mayoría de los días no se vende. */
        INTERMITTENT,
        /** Creciente a lo largo de los 180 días. */
        GROWING,
        /** Decreciente. */
        DECLINING,
        /** Sin movimiento: tiene stock pero no se vende. */
        NONE
    }

    record Supplier(String key, String name, String contactName, String phone, String email, int leadTimeDays,
                    String notes) {
    }

    record Template(String key, String category, String name, String brand, String companyCode, int item,
                    ProductUnit unit, BigDecimal cost, BigDecimal price, int minStock, int shelfLifeDays,
                    Pattern pattern, double baseDaily, int packSize, String supplierKey, String tags) {

        String barcode() {
            return Ean13.of(companyCode, item);
        }

        boolean perishable() {
            return shelfLifeDays > 0;
        }

        boolean hasTag(char tag) {
            return tags.indexOf(tag) >= 0;
        }
    }

    static final List<Supplier> SUPPLIERS = List.of(
            new Supplier("PAMPA", "Distribuidora La Pampa", "Marta Suárez", "11 4555-0101", "pedidos@lapampa.example",
                    2, "Lácteos, fiambres y huevos. Reparte martes, jueves y sábados."),
            new Supplier("SANJORGE", "Panificadora San Jorge", "Héctor Villalba", "11 4633-2290",
                    "ventas@sanjorge.example", 1, "Pan y tapas frescas todos los días."),
            new Supplier("PUERTO", "Mayorista El Puerto", "Graciela Ortiz", "341 425-7788", "mayorista@elpuerto.example",
                    4, "Almacén, conservas, golosinas y snacks. Pedido mínimo $150.000."),
            new Supplier("LITORAL", "Bebidas del Litoral", "Diego Ferraro", "341 437-1200",
                    "pedidos@bebidaslitoral.example", 3, "Aguas, gaseosas, cervezas y vinos."),
            new Supplier("LIMPIEZA", "Limpieza Total SRL", "Silvia Paredes", "11 4789-3321",
                    "ventas@limpiezatotal.example", 5, "Limpieza e higiene."),
            new Supplier("POLAR", "Frío Polar Distribuciones", "Raúl Medina", "341 456-9087", "frio@polar.example", 4,
                    "Congelados y helados en cadena de frío."),
            new Supplier("FRUTOS", "Frutos del Valle", "Cecilia Luna", "351 468-5521", "hola@frutosdelvalle.example",
                    5, "Frutos secos y semillas a granel y envasados."),
            new Supplier("NATURAL", "Natural Food Distribuidora", "Matías Rivero", "351 422-6610",
                    "pedidos@naturalfood.example", 4, "Dietética, sin TACC, refrigerados veganos."),
            new Supplier("ESPECIAS", "Especias del Norte", "Rosa Mamaní", "387 431-0045", "ventas@especiasnorte.example",
                    7, "Especias y condimentos. Envíos desde Salta."),
            new Supplier("GOLOSINAS", "Distribuidora Dulce Kiosco", "Pablo Giménez", "11 4302-7765",
                    "pedidos@dulcekiosco.example", 3, "Golosinas y alfajores para kioscos."),
            new Supplier("DROGUERIA", "Droguería Central Norte", "Laura Benítez", "261 429-8810",
                    "ventas@centralnorte.example", 3, "Perfumería, botiquín y dermocosmética."));

    private static final String DATA = """
            leche|Lácteos|Leche entera La Pradera 1 L|La Pradera|2201|10001|UNIDAD|950|1400|15|12|STABLE|14|12|PAMPA|AMK
            leche-desc|Lácteos|Leche descremada La Pradera 1 L|La Pradera|2201|10002|UNIDAD|980|1450|8|12|STABLE|6|12|PAMPA|AM
            leche-choco|Lácteos|Leche chocolatada La Pradera 1 L|La Pradera|2201|10003|UNIDAD|1300|1950|6|20|WEEKEND|3|12|PAMPA|M
            yogur-bebible|Lácteos|Yogur bebible frutilla Vaquita 1 L|Vaquita|2202|20001|UNIDAD|1400|2100|8|25|MILD|4|6|PAMPA|AM
            yogur-firme|Lácteos|Yogur firme vainilla Vaquita 190 g|Vaquita|2202|20002|UNIDAD|520|800|12|21|STABLE|7|12|PAMPA|AMK
            crema|Lácteos|Crema de leche La Pradera 200 ml|La Pradera|2201|10004|UNIDAD|900|1350|4|30|WEEKEND|2|12|PAMPA|AM
            manteca|Lácteos|Manteca La Pradera 200 g|La Pradera|2201|10005|UNIDAD|1300|1950|6|60|STABLE|3|10|PAMPA|AM
            queso-untable|Lácteos|Queso untable La Pradera 290 g|La Pradera|2201|10006|UNIDAD|1700|2550|4|35|STABLE|2.5|6|PAMPA|M
            queso-cremoso|Fiambres y quesos|Queso cremoso Chacarero 500 g|Chacarero|2301|30001|UNIDAD|3900|5600|4|35|STABLE|2.5|4|PAMPA|AM
            queso-rallado|Fiambres y quesos|Queso rallado Chacarero 150 g|Chacarero|2301|30002|UNIDAD|1700|2500|4|60|WEEKEND|2|10|PAMPA|AM
            jamon|Fiambres y quesos|Jamón cocido feteado Chacarero 200 g|Chacarero|2301|30003|UNIDAD|2600|3800|4|20|WEEKEND|3|6|PAMPA|AM
            salame|Fiambres y quesos|Salame milán Chacarero 200 g|Chacarero|2301|30004|UNIDAD|2400|3500|3|60|WEEKEND|2|6|PAMPA|AM
            salchichas|Fiambres y quesos|Salchichas Chacarero x 6|Chacarero|2301|30005|PAQUETE|1300|1950|6|30|WEEKEND|3|12|PAMPA|AMK
            huevos|Almacén|Huevos blancos Granja Feliz x 12|Granja Feliz|2302|40001|CAJA|2200|3200|6|25|STABLE|5|10|PAMPA|AM
            dulce-leche|Almacén|Dulce de leche Dulce Valle 400 g|Dulce Valle|8101|10001|UNIDAD|1600|2400|5|240|STABLE|2.5|12|PUERTO|AM
            pan-lactal|Panificados|Pan lactal blanco Don Trigo 550 g|Don Trigo|3101|10001|UNIDAD|1900|2700|6|7|STABLE|6|6|SANJORGE|AMK
            pan-integral|Panificados|Pan lactal integral Don Trigo 550 g|Don Trigo|3101|10002|UNIDAD|2000|2900|4|7|GROWING|3|6|SANJORGE|AM
            tapas-empanadas|Panificados|Tapas para empanadas Don Trigo x 12|Don Trigo|3101|10003|PAQUETE|1200|1800|6|20|WEEKEND|4|12|SANJORGE|AM
            tostadas|Panificados|Tostadas de arroz Don Trigo 150 g|Don Trigo|3101|10004|UNIDAD|800|1250|4|120|DECLINING|2|12|SANJORGE|AM
            galletitas-agua|Galletitas y snacks|Galletitas de agua Crocantes 200 g|Crocantes|3102|10001|UNIDAD|650|1000|10|180|STABLE|6|24|PUERTO|AMK
            galletitas-dulces|Galletitas y snacks|Galletitas dulces vainilla Crocantes 300 g|Crocantes|3102|10002|UNIDAD|900|1400|8|180|WEEKEND|4|24|PUERTO|AMK
            fideos-spaghetti|Almacén|Fideos spaghetti Molino del Sur 500 g|Molino del Sur|5102|10001|UNIDAD|700|1100|12|540|STABLE|7|20|PUERTO|AM
            fideos-tirabuzon|Almacén|Fideos tirabuzón Molino del Sur 500 g|Molino del Sur|5102|10002|UNIDAD|700|1100|10|540|STABLE|5|20|PUERTO|AM
            arroz|Almacén|Arroz largo fino Molino del Sur 1 kg|Molino del Sur|5102|10003|UNIDAD|1100|1700|10|540|STABLE|5|10|PUERTO|AM
            polenta|Almacén|Polenta instantánea Molino del Sur 500 g|Molino del Sur|5102|10004|UNIDAD|650|1000|4|365|STABLE|2|20|PUERTO|AM
            harina|Almacén|Harina 000 Pampa Dorada 1 kg|Pampa Dorada|5101|10001|UNIDAD|600|950|10|300|STABLE|4|10|PUERTO|AM
            aceite|Almacén|Aceite de girasol Pampa Dorada 1,5 L|Pampa Dorada|5101|10002|UNIDAD|2400|3500|6|540|STABLE|3|12|PUERTO|AM
            azucar|Almacén|Azúcar Pampa Dorada 1 kg|Pampa Dorada|5101|10003|UNIDAD|950|1400|10|720|STABLE|5|10|PUERTO|AM
            yerba|Almacén|Yerba mate Yerbal 1 kg|Yerbal|5201|10001|UNIDAD|3300|4800|8|540|STABLE|5|10|PUERTO|AMK
            yerba-compuesta|Almacén|Yerba mate compuesta Yerbal 500 g|Yerbal|5201|10002|UNIDAD|1800|2600|4|540|GROWING|2|10|PUERTO|AM
            cafe|Almacén|Café molido Aroma Serrano 250 g|Aroma Serrano|5202|10001|UNIDAD|2900|4200|3|365|DECLINING|2|12|PUERTO|AM
            te|Almacén|Té negro en saquitos Aroma Serrano x 25|Aroma Serrano|5202|10002|CAJA|700|1100|4|540|STABLE|2|12|PUERTO|AM
            sopa-tomate|Conservas|Sopa de tomate en lata La Huerta 340 g|La Huerta|1234|50001|UNIDAD|1100|1650|4|1080|STABLE|0.45|12|PUERTO|AM
            tomate-triturado|Conservas|Tomate triturado La Huerta 520 g|La Huerta|1234|50002|UNIDAD|800|1200|8|540|STABLE|4|12|PUERTO|AM
            arvejas|Conservas|Arvejas en lata La Huerta 350 g|La Huerta|1234|50003|UNIDAD|600|900|4|720|STABLE|1.5|12|PUERTO|AM
            atun|Conservas|Atún al natural La Huerta 170 g|La Huerta|1234|50004|UNIDAD|1900|2800|4|900|STABLE|2|12|PUERTO|AM
            mermelada|Almacén|Mermelada de durazno Dulce Valle 454 g|Dulce Valle|8101|10002|UNIDAD|1500|2200|3|365|DECLINING|1.5|12|PUERTO|AM
            mayonesa|Almacén|Mayonesa Chef Casero 500 g|Chef Casero|5301|10001|UNIDAD|1600|2400|4|180|STABLE|2.5|12|PUERTO|AM
            salsa-bbq|Almacén|Salsa barbacoa ahumada Chef Casero 250 g|Chef Casero|5301|10002|UNIDAD|2100|3200|2|365|NONE|0|6|PUERTO|M
            sal|Almacén|Sal fina Salinas del Sur 500 g|Salinas del Sur|5302|10001|UNIDAD|450|700|4|0|STABLE|1.5|20|PUERTO|AM
            aceite-oliva|Almacén|Aceite de oliva extra virgen Olivar del Sur 500 ml|Olivar del Sur|5303|10001|UNIDAD|5200|7800|2|540|NONE|0|6|PUERTO|A
            oregano|Especias|Orégano Especiero del Norte 25 g|Especiero del Norte|9103|10001|UNIDAD|450|750|2|365|INTERMITTENT|0.25|6|ESPECIAS|AMD
            pimenton|Especias|Pimentón dulce Especiero del Norte 50 g|Especiero del Norte|9103|10002|UNIDAD|600|950|2|365|INTERMITTENT|0.2|6|ESPECIAS|AMD
            comino|Especias|Comino molido Especiero del Norte 25 g|Especiero del Norte|9103|10003|UNIDAD|500|800|2|365|INTERMITTENT|0.15|6|ESPECIAS|AMD
            agua|Bebidas|Agua mineral sin gas Sierra Azul 2 L|Sierra Azul|4101|10001|UNIDAD|650|1000|12|365|WEEKEND|8|6|LITORAL|AMK
            agua-chica|Bebidas|Agua mineral sin gas Sierra Azul 500 ml|Sierra Azul|4101|10002|UNIDAD|380|650|12|365|WEEKEND|5|12|LITORAL|DK
            soda|Bebidas|Soda en sifón Sierra Azul 1,5 L|Sierra Azul|4101|10003|UNIDAD|500|800|4|180|DECLINING|2|6|LITORAL|AM
            cola|Bebidas|Gaseosa cola Cumbre 2,25 L|Cumbre|4102|10001|UNIDAD|1500|2300|12|180|WEEKEND|9|8|LITORAL|AMK
            lima|Bebidas|Gaseosa lima-limón Cumbre 2,25 L|Cumbre|4102|10002|UNIDAD|1400|2200|8|180|WEEKEND|5|8|LITORAL|AM
            cola-chica|Bebidas|Gaseosa cola Cumbre 500 ml|Cumbre|4102|10003|UNIDAD|700|1100|12|180|WEEKEND|6|12|LITORAL|K
            jugo-polvo|Bebidas|Jugo en polvo naranja Cumbre 18 g|Cumbre|4102|10004|UNIDAD|180|300|20|365|STABLE|6|20|LITORAL|AMK
            isotonica|Bebidas|Bebida isotónica Energía+ 500 ml|Energía+|4103|10001|UNIDAD|900|1400|6|270|GROWING|3|6|LITORAL|AMK
            energizante|Bebidas|Bebida energizante Energía+ lata 473 ml|Energía+|4103|10002|UNIDAD|1300|2000|6|300|WEEKEND|3|6|LITORAL|K
            cerveza|Bebidas alcohólicas|Cerveza rubia Cervecería Andina lata 473 ml|Cervecería Andina|4201|10001|UNIDAD|1100|1700|24|240|WEEKEND|12|24|LITORAL|AM
            vino|Bebidas alcohólicas|Vino tinto Malbec Viñas del Valle 750 ml|Viñas del Valle|4202|10001|UNIDAD|3400|5200|6|0|WEEKEND|3|6|LITORAL|AM
            papas|Galletitas y snacks|Papas fritas clásicas Crujis 150 g|Crujis|7101|10001|UNIDAD|1500|2300|8|150|WEEKEND|5|12|PUERTO|AMK
            palitos|Galletitas y snacks|Palitos salados Crujis 120 g|Crujis|7101|10002|UNIDAD|900|1400|6|150|WEEKEND|3|12|PUERTO|AMK
            mani|Galletitas y snacks|Maní salado Crujis 200 g|Crujis|7101|10003|UNIDAD|1000|1500|4|180|WEEKEND|2|12|PUERTO|AM
            alfajor|Golosinas|Alfajor triple chocolate Dulce Valle 70 g|Dulce Valle|8101|10003|UNIDAD|550|900|24|120|STABLE|10|24|GOLOSINAS|AMK
            chocolate|Golosinas|Chocolate con leche Dulce Valle 100 g|Dulce Valle|8101|10004|UNIDAD|1400|2100|6|240|STABLE|3|12|GOLOSINAS|AMK
            caramelos|Golosinas|Caramelos masticables frutales Dulce Valle x 10|Dulce Valle|8101|10005|PAQUETE|150|250|20|300|STABLE|8|50|GOLOSINAS|K
            chicles|Golosinas|Chicles menta Frescor x 5|Frescor|7102|10001|PAQUETE|250|400|20|365|STABLE|6|40|GOLOSINAS|K
            turron|Golosinas|Turrón de maní Dulce Valle 25 g|Dulce Valle|8101|10006|UNIDAD|200|350|20|180|STABLE|6|50|GOLOSINAS|K
            chupetin|Golosinas|Chupetín frutal Dulce Valle|Dulce Valle|8101|10007|UNIDAD|120|200|20|365|STABLE|5|50|GOLOSINAS|K
            barras-cereal|Galletitas y snacks|Barras de cereal Energía+ x 6|Energía+|4103|10003|CAJA|1800|2700|4|150|WEEKEND|3|12|NATURAL|DK
            lavandina|Limpieza|Lavandina Brillo Hogar 1 L|Brillo Hogar|6101|10001|UNIDAD|700|1100|6|0|STABLE|3|12|LIMPIEZA|AM
            detergente|Limpieza|Detergente limón Brillo Hogar 750 ml|Brillo Hogar|6101|10002|UNIDAD|1300|1950|6|0|STABLE|3|12|LIMPIEZA|AM
            jabon-polvo|Limpieza|Jabón en polvo Brillo Hogar 800 g|Brillo Hogar|6101|10003|UNIDAD|2600|3800|3|0|DECLINING|1.5|10|LIMPIEZA|AM
            suavizante|Limpieza|Suavizante Brillo Hogar 900 ml|Brillo Hogar|6101|10004|UNIDAD|1800|2700|3|0|STABLE|1.5|12|LIMPIEZA|M
            esponja|Limpieza|Esponja multiuso Brillo Hogar x 3|Brillo Hogar|6101|10005|PAQUETE|800|1200|3|0|INTERMITTENT|0.6|12|LIMPIEZA|AM
            papel-higienico|Higiene y perfumería|Papel higiénico Suavecito x 4|Suavecito|6102|10001|PAQUETE|1700|2500|8|0|STABLE|4|12|LIMPIEZA|AMK
            shampoo|Higiene y perfumería|Shampoo Suavecito 400 ml|Suavecito|6102|10002|UNIDAD|2600|3900|3|0|STABLE|1.5|12|LIMPIEZA|MF
            pasta-dental|Higiene y perfumería|Pasta dental Suavecito 90 g|Suavecito|6102|10003|UNIDAD|1200|1800|4|0|STABLE|2|12|LIMPIEZA|MF
            panuelos|Higiene y perfumería|Pañuelos descartables Suavecito x 10|Suavecito|6102|10004|PAQUETE|300|500|10|0|STABLE|3|24|LIMPIEZA|KF
            hamburguesas|Congelados|Hamburguesas de carne Frío Polar x 4|Frío Polar|2401|10001|CAJA|2200|3300|4|180|WEEKEND|3|10|POLAR|AM
            helado|Congelados|Helado de dulce de leche Frío Polar 1 kg|Frío Polar|2401|10002|UNIDAD|4500|6800|2|240|GROWING|1.5|6|POLAR|M
            papas-prefritas|Congelados|Papas prefritas Frío Polar 700 g|Frío Polar|2401|10003|UNIDAD|2100|3100|3|270|STABLE|2|10|POLAR|M
            almendras|Frutos secos|Almendras peladas Cosecha Natural 250 g|Cosecha Natural|9101|10001|UNIDAD|4200|6300|4|240|STABLE|3|10|FRUTOS|D
            nueces|Frutos secos|Nueces mariposa Cosecha Natural 250 g|Cosecha Natural|9101|10002|UNIDAD|3900|5900|4|180|STABLE|3|10|FRUTOS|D
            mix-frutos|Frutos secos|Mix de frutos secos Cosecha Natural 200 g|Cosecha Natural|9101|10003|UNIDAD|3100|4700|6|180|GROWING|4|10|FRUTOS|D
            pasas|Frutos secos|Pasas de uva Cosecha Natural 250 g|Cosecha Natural|9101|10004|UNIDAD|1500|2300|3|240|STABLE|2|10|FRUTOS|D
            castanas|Frutos secos|Castañas de cajú Cosecha Natural 200 g|Cosecha Natural|9101|10005|UNIDAD|5200|7800|2|240|STABLE|1.5|10|FRUTOS|D
            mani-tostado|Frutos secos|Maní tostado sin sal Cosecha Natural 500 g|Cosecha Natural|9101|10006|UNIDAD|1900|2800|3|180|STABLE|2|10|FRUTOS|D
            datiles|Frutos secos|Dátiles sin carozo Cosecha Natural 250 g|Cosecha Natural|9101|10007|UNIDAD|2800|4200|2|240|STABLE|1|10|FRUTOS|D
            arandanos|Frutos secos|Arándanos deshidratados Cosecha Natural 150 g|Cosecha Natural|9101|10008|UNIDAD|3200|4800|2|240|GROWING|1.2|10|FRUTOS|D
            chips-banana|Frutos secos|Chips de banana Cosecha Natural 100 g|Cosecha Natural|9101|10009|UNIDAD|1300|1950|2|180|WEEKEND|1.5|10|FRUTOS|D
            ciruelas|Frutos secos|Ciruelas desecadas Cosecha Natural 250 g|Cosecha Natural|9101|10010|UNIDAD|1900|2800|2|240|DECLINING|1|10|FRUTOS|D
            chia|Semillas|Semillas de chía Cosecha Natural 250 g|Cosecha Natural|9101|10011|UNIDAD|1800|2700|4|365|STABLE|3|10|FRUTOS|D
            lino|Semillas|Semillas de lino Cosecha Natural 250 g|Cosecha Natural|9101|10012|UNIDAD|900|1400|3|365|DECLINING|2|10|FRUTOS|D
            girasol|Semillas|Semillas de girasol peladas Cosecha Natural 200 g|Cosecha Natural|9101|10013|UNIDAD|1100|1700|3|240|STABLE|2|10|FRUTOS|D
            sesamo|Semillas|Semillas de sésamo integral Cosecha Natural 250 g|Cosecha Natural|9101|10014|UNIDAD|1400|2100|2|365|INTERMITTENT|0.4|10|FRUTOS|D
            mix-semillas|Semillas|Mix de semillas para ensaladas Cosecha Natural 200 g|Cosecha Natural|9101|10015|UNIDAD|1600|2400|2|300|STABLE|1.2|10|FRUTOS|D
            avena|Cereales y harinas|Avena arrollada Campo Sano 500 g|Campo Sano|9104|10001|UNIDAD|900|1400|6|300|STABLE|5|12|NATURAL|D
            granola|Cereales y harinas|Granola con miel Campo Sano 400 g|Campo Sano|9104|10002|UNIDAD|2600|3900|4|180|GROWING|3|12|NATURAL|D
            copos-maiz|Cereales y harinas|Copos de maíz sin azúcar Campo Sano 300 g|Campo Sano|9104|10003|UNIDAD|1500|2250|3|240|STABLE|2|12|NATURAL|D
            mix-cereales|Cereales y harinas|Mix de cereales con frutos rojos Campo Sano 300 g|Campo Sano|9104|10004|UNIDAD|2200|3300|2|240|WEEKEND|1.5|12|NATURAL|D
            salvado|Cereales y harinas|Salvado de avena Campo Sano 400 g|Campo Sano|9104|10005|UNIDAD|800|1200|2|300|DECLINING|1|12|NATURAL|D
            harina-integral|Cereales y harinas|Harina integral Vida Verde 1 kg|Vida Verde|9102|10001|UNIDAD|1100|1700|4|180|STABLE|3|10|NATURAL|D
            harina-almendras|Cereales y harinas|Harina de almendras Vida Verde 250 g|Vida Verde|9102|10002|UNIDAD|4800|7200|1|180|INTERMITTENT|0.35|6|NATURAL|D
            harina-garbanzos|Cereales y harinas|Harina de garbanzos Vida Verde 500 g|Vida Verde|9102|10003|UNIDAD|1600|2400|2|240|STABLE|1.5|10|NATURAL|D
            harina-arroz|Cereales y harinas|Harina de arroz Vida Verde 1 kg|Vida Verde|9102|10004|UNIDAD|1500|2250|2|240|STABLE|1.2|10|NATURAL|D
            premezcla|Sin TACC|Premezcla sin TACC Vida Verde 1 kg|Vida Verde|9102|10005|UNIDAD|2300|3400|2|240|STABLE|1.5|10|NATURAL|D
            fideos-arroz|Sin TACC|Fideos de arroz sin TACC Molino del Sur 500 g|Molino del Sur|5102|10005|UNIDAD|1700|2500|2|540|STABLE|1.5|20|NATURAL|D
            galletas-arroz|Sin TACC|Galletas de arroz integrales Campo Sano 150 g|Campo Sano|9104|10006|UNIDAD|900|1350|4|180|STABLE|3|12|NATURAL|D
            galletitas-avena|Galletitas y snacks|Galletitas de avena y pasas Campo Sano 200 g|Campo Sano|9104|10007|UNIDAD|1400|2100|3|180|STABLE|2.5|12|NATURAL|D
            alfajor-algarroba|Golosinas|Alfajor de algarroba Vida Verde 50 g|Vida Verde|9102|10006|UNIDAD|700|1100|12|120|STABLE|4|24|NATURAL|D
            lentejas|Legumbres|Lentejas Campo Sano 500 g|Campo Sano|9104|10008|UNIDAD|1200|1800|3|540|STABLE|2|12|NATURAL|D
            garbanzos|Legumbres|Garbanzos Campo Sano 500 g|Campo Sano|9104|10009|UNIDAD|1300|1950|2|540|STABLE|1.5|12|NATURAL|D
            porotos-negros|Legumbres|Porotos negros Campo Sano 500 g|Campo Sano|9104|10010|UNIDAD|1200|1800|2|540|STABLE|1|12|NATURAL|D
            quinoa|Legumbres|Quinoa blanca Campo Sano 500 g|Campo Sano|9104|10011|UNIDAD|3500|5200|2|540|DECLINING|1.2|12|NATURAL|D
            arroz-yamani|Legumbres|Arroz yamaní Campo Sano 1 kg|Campo Sano|9104|10012|UNIDAD|2100|3100|2|365|STABLE|2|10|NATURAL|D
            leche-almendras|Bebidas vegetales|Bebida de almendras Vida Verde 1 L|Vida Verde|9102|10007|UNIDAD|2400|3500|4|60|GROWING|3|6|NATURAL|D
            bebida-avena|Bebidas vegetales|Bebida de avena Vida Verde 1 L|Vida Verde|9102|10008|UNIDAD|2200|3300|3|60|GROWING|2|6|NATURAL|D
            yogur-coco|Refrigerados|Yogur vegetal de coco Vida Verde 170 g|Vida Verde|9102|10009|UNIDAD|1300|1950|4|20|STABLE|3|12|NATURAL|D
            tofu|Refrigerados|Tofu firme Vida Verde 300 g|Vida Verde|9102|10010|UNIDAD|2300|3400|2|25|STABLE|1.5|6|NATURAL|D
            hummus|Refrigerados|Hummus clásico Vida Verde 200 g|Vida Verde|9102|10011|UNIDAD|1900|2800|3|18|STABLE|2|6|NATURAL|D
            jugo-naranja|Refrigerados|Jugo de naranja exprimido Vida Verde 1 L|Vida Verde|9102|10012|UNIDAD|2100|3100|3|12|WEEKEND|2|6|NATURAL|D
            kombucha|Refrigerados|Kombucha de jengibre Vida Verde 500 ml|Vida Verde|9102|10013|UNIDAD|1800|2700|3|60|GROWING|2|6|NATURAL|D
            pan-centeno|Panificados|Pan de centeno Don Trigo 400 g|Don Trigo|3101|10005|UNIDAD|2100|3100|2|10|STABLE|2|6|SANJORGE|D
            miel|Endulzantes|Miel pura Campo Sano 500 g|Campo Sano|9104|10013|UNIDAD|3200|4800|2|720|STABLE|1.5|12|NATURAL|D
            stevia|Endulzantes|Stevia líquida Vida Verde 200 ml|Vida Verde|9102|10014|UNIDAD|1500|2250|2|540|STABLE|1.5|12|NATURAL|D
            azucar-mascabo|Endulzantes|Azúcar mascabo Campo Sano 500 g|Campo Sano|9104|10014|UNIDAD|1400|2100|2|540|STABLE|1.5|12|NATURAL|D
            mantequilla-mani|Untables|Mantequilla de maní Vida Verde 380 g|Vida Verde|9102|10015|UNIDAD|3100|4600|2|270|GROWING|2|6|NATURAL|D
            tahini|Untables|Tahini Vida Verde 300 g|Vida Verde|9102|10016|UNIDAD|3600|5400|1|365|INTERMITTENT|0.3|6|NATURAL|D
            aceite-coco|Untables|Aceite de coco Vida Verde 360 ml|Vida Verde|9102|10017|UNIDAD|4200|6200|1|540|DECLINING|1|6|NATURAL|D
            levadura|Suplementos|Levadura nutricional Vida Verde 150 g|Vida Verde|9102|10018|UNIDAD|3300|4900|1|365|GROWING|1|6|NATURAL|D
            colageno|Suplementos|Colágeno hidrolizado Energía+ 300 g|Energía+|4103|10004|UNIDAD|9800|14500|1|540|NONE|0|4|NATURAL|D
            te-verde|Infusiones|Té verde en saquitos Aroma Serrano x 20|Aroma Serrano|5202|10003|CAJA|1100|1650|2|540|STABLE|1.5|12|NATURAL|D
            yerba-organica|Infusiones|Yerba mate orgánica Yerbal 500 g|Yerbal|5201|10003|UNIDAD|2600|3900|2|540|STABLE|1.5|10|NATURAL|D
            curcuma|Especias|Cúrcuma molida Especiero del Norte 50 g|Especiero del Norte|9103|10004|UNIDAD|900|1400|1|365|INTERMITTENT|0.3|6|ESPECIAS|D
            canela|Especias|Canela en rama Especiero del Norte 25 g|Especiero del Norte|9103|10005|UNIDAD|800|1200|1|365|INTERMITTENT|0.25|6|ESPECIAS|D
            jengibre|Especias|Jengibre en polvo Especiero del Norte 25 g|Especiero del Norte|9103|10006|UNIDAD|800|1200|1|365|INTERMITTENT|0.2|6|ESPECIAS|D
            alcohol-gel|Higiene y perfumería|Alcohol en gel Suavecito 250 ml|Suavecito|6102|10005|UNIDAD|1400|2100|4|730|DECLINING|1.5|12|DROGUERIA|F
            protector|Dermocosmética|Protector solar FPS 50 Suavecito 200 ml|Suavecito|6102|10006|UNIDAD|8200|12300|3|730|GROWING|1|6|DROGUERIA|F
            repelente|Dermocosmética|Repelente de mosquitos Cura+ 200 ml|Cura+|6103|10001|UNIDAD|3100|4600|3|730|GROWING|1|6|DROGUERIA|F
            curitas|Botiquín|Apósitos adhesivos Cura+ x 20|Cura+|6103|10002|CAJA|1100|1700|4|1095|STABLE|1.5|12|DROGUERIA|F
            algodon|Botiquín|Algodón hidrófilo Cura+ 140 g|Cura+|6103|10003|UNIDAD|1300|1950|3|0|STABLE|1.2|12|DROGUERIA|F
            vitamina-c|Suplementos|Vitamina C efervescente Energía+ x 10|Energía+|4103|10005|UNIDAD|2600|3900|3|540|STABLE|1.5|12|DROGUERIA|F
            panales|Bebés|Pañales Bebito talle G x 30|Bebito|6104|10001|PAQUETE|9800|14200|3|0|STABLE|1.5|4|DROGUERIA|F
            toallitas|Bebés|Toallitas húmedas Bebito x 50|Bebito|6104|10002|PAQUETE|1500|2300|4|540|STABLE|2|12|DROGUERIA|F
            termometro|Botiquín|Termómetro digital Cura+|Cura+|6103|10004|UNIDAD|4200|6400|1|0|INTERMITTENT|0.2|4|DROGUERIA|F
            jabon-glicerina|Higiene y perfumería|Jabón de glicerina Suavecito 90 g|Suavecito|6102|10007|UNIDAD|700|1100|4|0|STABLE|1.5|12|DROGUERIA|F
            cepillo|Higiene y perfumería|Cepillo dental medio Suavecito|Suavecito|6102|10008|UNIDAD|900|1400|3|0|STABLE|1|12|DROGUERIA|F
            """;

    static final List<Template> ALL = parse();

    private static final Map<String, Template> BY_KEY = index(ALL);

    private DemoCatalog() {
    }

    static Template byKey(String key) {
        Template template = BY_KEY.get(key);
        if (template == null) {
            throw new IllegalArgumentException("Producto demo inexistente: " + key);
        }
        return template;
    }

    /** Productos del rubro (letra A, M, D, K o F), en el orden del catálogo. */
    static List<Template> forTag(char tag) {
        return ALL.stream().filter(template -> template.hasTag(tag)).toList();
    }

    static Supplier supplier(String key) {
        return SUPPLIERS.stream().filter(supplier -> supplier.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Proveedor demo inexistente: " + key));
    }

    private static List<Template> parse() {
        List<Template> templates = new ArrayList<>();
        for (String line : DATA.strip().split("\\R")) {
            String[] f = line.strip().split("\\|");
            if (f.length != 16) {
                throw new IllegalStateException("Línea de catálogo inválida: " + line);
            }
            templates.add(new Template(f[0], f[1], f[2], f[3], f[4], Integer.parseInt(f[5]),
                    ProductUnit.valueOf(f[6]), new BigDecimal(f[7]), new BigDecimal(f[8]), Integer.parseInt(f[9]),
                    Integer.parseInt(f[10]), Pattern.valueOf(f[11]), Double.parseDouble(f[12]),
                    Integer.parseInt(f[13]), f[14], f[15]));
        }
        return Collections.unmodifiableList(templates);
    }

    private static Map<String, Template> index(List<Template> templates) {
        Map<String, Template> map = new LinkedHashMap<>();
        for (Template template : templates) {
            if (map.put(template.key(), template) != null) {
                throw new IllegalStateException("Clave de producto demo repetida: " + template.key());
            }
        }
        return map;
    }
}
