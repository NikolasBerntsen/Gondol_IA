package com.gondolia.seed;

import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.TenantEventType;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.domain.user.Role;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * El "mundo" de los datos demo (SPEC §11): dueños y soporte de GondolIA, los tres comercios con historia rica
 * (Almacén Don Pepe, Dietética Vida Sana y Minimercado El Sol), el kiosco deshabilitado y los comercios livianos que
 * alimentan las métricas de crecimiento y churn. Todo es ficticio.
 */
final class DemoWorld {

    static final String TENANT_PASSWORD = "Demo2026!";
    static final String PLATFORM_PASSWORD = "Gondolia2026!";

    /** Días de historia simulada día a día de los comercios ricos. */
    static final int RICH_HISTORY_DAYS = 180;

    /** Historia de los comercios livianos (o menos, si son más nuevos). */
    static final int LIGHT_HISTORY_DAYS = 45;

    /** Cómo le llegan las ventas a GondolIA en una sucursal. */
    enum Channel {
        /** POS GondolIA: turnos de caja, tickets, pagos y anulaciones (SPEC §15). */
        POS_GONDOLIA,
        /** POS propio del cliente por webhook con API key ({@code source = POS}, {@code S-EXT-...}). */
        EXTERNAL_POS,
        /** Primero importaba un CSV de ventas por día y después conectó su POS por API. */
        CSV_THEN_EXTERNAL,
        /** Venta manual diaria cargada por el administrador. */
        MANUAL
    }

    /** Quién atiende cada caja del POS GondolIA. Caja 1: turno mañana y tarde; Caja 2: refuerzo algunos días. */
    record PosStaff(String morning, String afternoon, String secondRegister, Set<DayOfWeek> secondRegisterDays,
                    int secondFromHour, int secondToHour) {
    }

    record BranchSpec(String key, String name, String code, String address, String city, String province,
                      String phone, double scale, Channel channel, String externalPrefix, PosStaff staff) {
    }

    record UserSpec(String email, String fullName, Role role, List<String> branchKeys, int lastLoginDaysAgo) {
    }

    record EventSpec(TenantEventType type, int daysAgo, String fromValue, String toValue, String reason) {
    }

    record TenantSpec(String key, String name, String legalName, String taxId, BusinessType businessType,
                      TenantPlan plan, TenantStatus status, String statusReason, int statusChangedDaysAgo,
                      String contactName, String contactEmail, String contactPhone, String address, String city,
                      String province, String notes, StockRotation rotation, Set<TenantModule> modules,
                      int createdDaysAgo, List<BranchSpec> branches, List<UserSpec> users, char catalogTag,
                      int productLimit, List<String> excludedProducts, boolean rich, List<EventSpec> events) {

        /** Último día simulado (días atrás): hoy si está activo, si no el día en que cambió de estado. */
        int lastSimulatedDaysAgo() {
            return status == TenantStatus.ACTIVE ? 0 : statusChangedDaysAgo;
        }

        int historyDays() {
            int available = createdDaysAgo - lastSimulatedDaysAgo() - 1;
            return Math.max(0, Math.min(rich ? RICH_HISTORY_DAYS : LIGHT_HISTORY_DAYS, available));
        }

        BranchSpec branch(String branchKey) {
            return branches.stream().filter(branch -> branch.key().equals(branchKey)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Sucursal demo inexistente: " + branchKey));
        }
    }

    record PlatformUser(String email, String fullName, Role role, int lastLoginDaysAgo) {
    }

    static final List<PlatformUser> PLATFORM_USERS = List.of(
            new PlatformUser("dueno@gondolia.app", "Federico Almada", Role.PLATFORM_OWNER, 0),
            new PlatformUser("socia@gondolia.app", "Valeria Quiroga", Role.PLATFORM_OWNER, 1),
            new PlatformUser("soporte@gondolia.app", "Sofía Martínez", Role.SUPPORT_AGENT, 0),
            new PlatformUser("soporte2@gondolia.app", "Tomás Aguirre", Role.SUPPORT_AGENT, 0));

    static final String DON_PEPE = "donpepe";
    static final String VIDA_SANA = "vidasana";
    static final String EL_SOL = "elsol";
    static final String LA_ESQUINA = "laesquina";

    private static final Set<DayOfWeek> FRI_SAT = EnumSet.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
    private static final Set<DayOfWeek> SAT = EnumSet.of(DayOfWeek.SATURDAY);

    private DemoWorld() {
    }

    static List<TenantSpec> tenants() {
        List<TenantSpec> tenants = new ArrayList<>();
        tenants.add(donPepe());
        tenants.add(vidaSana());
        tenants.add(elSol());
        tenants.add(laEsquina());
        tenants.addAll(lightTenants());
        return tenants;
    }

    // ------------------------------------------------------------------ comercios con historia rica

    private static TenantSpec donPepe() {
        return new TenantSpec(DON_PEPE, "Almacén Don Pepe", "Fernández José Luis", "20-18456732-5",
                BusinessType.ALMACEN, TenantPlan.BASICO, TenantStatus.ACTIVE, null, 0, "Marta Fernández",
                "admin@donpepe.com", "11 4981-2233", "Av. Boedo 1450", "CABA", "Ciudad Autónoma de Buenos Aires",
                "Almacén de barrio familiar. Usa el POS GondolIA desde el alta.", StockRotation.FIFO,
                EnumSet.of(TenantModule.POS_GONDOLIA), 335,
                List.of(new BranchSpec("PRI", "Sucursal Principal", "PRI", "Av. Boedo 1450", "CABA",
                        "Ciudad Autónoma de Buenos Aires", "11 4981-2233", 0.9, Channel.POS_GONDOLIA, null,
                        new PosStaff("cajero@donpepe.com", "empleado@donpepe.com", "admin@donpepe.com", SAT, 10,
                                14))),
                List.of(new UserSpec("jefe@donpepe.com", "José Fernández", Role.TENANT_BOSS, List.of(), 1),
                        new UserSpec("admin@donpepe.com", "Marta Fernández", Role.TENANT_ADMIN, List.of(), 0),
                        new UserSpec("empleado@donpepe.com", "Lucas Benítez", Role.TENANT_EMPLOYEE, List.of("PRI"), 0),
                        new UserSpec("cajero@donpepe.com", "Rocío Paz", Role.TENANT_CASHIER, List.of("PRI"), 0)),
                'A', 60, List.of(), true,
                List.of(new EventSpec(TenantEventType.CREATED, 335, null, "BASICO", null),
                        new EventSpec(TenantEventType.MODULE_ENABLED, 335, "POS_GONDOLIA", "true", null)));
    }

    private static TenantSpec vidaSana() {
        return new TenantSpec(VIDA_SANA, "Dietética Vida Sana", "Vida Sana SRL", "30-71654321-8",
                BusinessType.DIETETICA, TenantPlan.PROFESIONAL, TenantStatus.ACTIVE, null, 0, "Carolina Méndez",
                "admin@vidasana.com", "351 468-2201", "Av. Hipólito Yrigoyen 480", "Córdoba", "Córdoba",
                "Dos locales en Córdoba. Vende con su propio POS conectado por API.", StockRotation.FEFO,
                EnumSet.of(TenantModule.POS_INTEGRATION, TenantModule.MULTI_BRANCH), 290,
                List.of(new BranchSpec("NCB", "Sucursal Nueva Córdoba", "NCB", "Av. Hipólito Yrigoyen 480",
                                "Córdoba", "Córdoba", "351 468-2201", 1.0, Channel.EXTERNAL_POS, "NC", null),
                        new BranchSpec("CDR", "Sucursal Cerro de las Rosas", "CDR", "Av. Rafael Núñez 4320",
                                "Córdoba", "Córdoba", "351 481-7730", 0.75, Channel.EXTERNAL_POS, "CR", null)),
                List.of(new UserSpec("jefe@vidasana.com", "Ricardo Méndez", Role.TENANT_BOSS, List.of(), 2),
                        new UserSpec("admin@vidasana.com", "Carolina Méndez", Role.TENANT_ADMIN, List.of(), 0),
                        new UserSpec("empleado@vidasana.com", "Agustina Torres", Role.TENANT_EMPLOYEE,
                                List.of("NCB"), 0),
                        new UserSpec("empleado.cerro@vidasana.com", "Franco Luna", Role.TENANT_EMPLOYEE,
                                List.of("CDR"), 1)),
                'D', 60, List.of(), true,
                List.of(new EventSpec(TenantEventType.CREATED, 290, null, "PROFESIONAL", null),
                        new EventSpec(TenantEventType.MODULE_ENABLED, 290, "POS_INTEGRATION", "true", null),
                        new EventSpec(TenantEventType.MODULE_ENABLED, 290, "MULTI_BRANCH", "true", null)));
    }

    private static TenantSpec elSol() {
        return new TenantSpec(EL_SOL, "Minimercado El Sol", "El Sol Rosario SA", "30-71234567-9",
                BusinessType.MINIMERCADO, TenantPlan.PROFESIONAL, TenantStatus.ACTIVE, null, 0, "Laura Gómez",
                "admin@elsol.com", "341 421-5500", "Córdoba 1650", "Rosario", "Santa Fe",
                "Tres sucursales en Rosario. Migró su planilla de stock con la importación masiva.",
                StockRotation.FIFO,
                EnumSet.of(TenantModule.POS_GONDOLIA, TenantModule.POS_INTEGRATION, TenantModule.MULTI_BRANCH), 190,
                List.of(new BranchSpec("CEN", "Sucursal Centro", "CEN", "Córdoba 1650", "Rosario", "Santa Fe",
                                "341 421-5500", 1.3, Channel.POS_GONDOLIA, null,
                                new PosStaff("cajero@elsol.com", "empleado@elsol.com", "admin@elsol.com", FRI_SAT, 17,
                                        21)),
                        new BranchSpec("FIS", "Sucursal Fisherton", "FIS", "Av. Eva Perón 8250", "Rosario",
                                "Santa Fe", "341 452-9911", 1.0, Channel.POS_GONDOLIA, null,
                                new PosStaff("cajero.fisherton@elsol.com", "cajera.tarde@elsol.com",
                                        "empleado@elsol.com", SAT, 10, 14)),
                        new BranchSpec("ECH", "Sucursal Echesortu", "ECH", "Mendoza 3890", "Rosario", "Santa Fe",
                                "341 438-1204", 0.8, Channel.CSV_THEN_EXTERNAL, "EC", null)),
                List.of(new UserSpec("jefe@elsol.com", "Roberto Gómez", Role.TENANT_BOSS, List.of(), 1),
                        new UserSpec("admin@elsol.com", "Laura Gómez", Role.TENANT_ADMIN, List.of(), 0),
                        new UserSpec("empleado@elsol.com", "Martín Acosta", Role.TENANT_EMPLOYEE,
                                List.of("CEN", "FIS"), 0),
                        new UserSpec("empleado.echesortu@elsol.com", "Julieta Romero", Role.TENANT_EMPLOYEE,
                                List.of("ECH"), 0),
                        new UserSpec("cajero@elsol.com", "Brenda Sosa", Role.TENANT_CASHIER, List.of("CEN"), 0),
                        new UserSpec("cajero.fisherton@elsol.com", "Nicolás Ferreyra", Role.TENANT_CASHIER,
                                List.of("FIS"), 0),
                        new UserSpec("cajera.tarde@elsol.com", "Micaela Ruiz", Role.TENANT_CASHIER, List.of("FIS"),
                                1)),
                'M', 66, List.of(), true,
                List.of(new EventSpec(TenantEventType.CREATED, 190, null, "PROFESIONAL", null),
                        new EventSpec(TenantEventType.MODULE_ENABLED, 190, "POS_GONDOLIA", "true", null),
                        new EventSpec(TenantEventType.MODULE_ENABLED, 190, "POS_INTEGRATION", "true", null),
                        new EventSpec(TenantEventType.MODULE_ENABLED, 190, "MULTI_BRANCH", "true", null)));
    }

    private static TenantSpec laEsquina() {
        return new TenantSpec(LA_ESQUINA, "Kiosco La Esquina", "Correa Natalia", "27-30987654-1",
                BusinessType.KIOSCO, TenantPlan.FREEMIUM, TenantStatus.DISABLED,
                "Deshabilitado por uso indebido: el usuario administrador se compartía entre varios locales", 18,
                "Natalia Correa", "admin@laesquina.com", "221 455-7812", "Calle 7 n.º 1234", "La Plata",
                "Buenos Aires", "Kiosco con POS GondolIA en plan gratuito.", StockRotation.FIFO,
                EnumSet.of(TenantModule.POS_GONDOLIA), 150,
                List.of(new BranchSpec("PRI", "Sucursal Principal", "PRI", "Calle 7 n.º 1234", "La Plata",
                        "Buenos Aires", "221 455-7812", 0.8, Channel.POS_GONDOLIA, null,
                        new PosStaff("admin@laesquina.com", "empleado@laesquina.com", null, Set.of(), 0, 0))),
                List.of(new UserSpec("jefe@laesquina.com", "Hugo Correa", Role.TENANT_BOSS, List.of(), 25),
                        new UserSpec("admin@laesquina.com", "Natalia Correa", Role.TENANT_ADMIN, List.of(), 19),
                        new UserSpec("empleado@laesquina.com", "Ezequiel Díaz", Role.TENANT_EMPLOYEE, List.of("PRI"),
                                19)),
                'K', 18, List.of(), false,
                List.of(new EventSpec(TenantEventType.CREATED, 150, null, "FREEMIUM", null),
                        new EventSpec(TenantEventType.MODULE_ENABLED, 150, "POS_GONDOLIA", "true", null),
                        new EventSpec(TenantEventType.DISABLED, 18, "ACTIVE", "DISABLED",
                                "Deshabilitado por uso indebido: el usuario administrador se compartía entre varios "
                                        + "locales")));
    }

    // ------------------------------------------------------------------ comercios livianos

    private record Light(String key, String name, String legalName, BusinessType type, TenantPlan plan,
                         TenantStatus status, String reason, int statusDaysAgo, String city, String province,
                         String street, int createdDaysAgo, List<String> branchNames, Set<TenantModule> modules,
                         char tag, int products, String domain, String[] people, int loginDaysAgo,
                         List<EventSpec> extraEvents) {
    }

    private static List<TenantSpec> lightTenants() {
        Set<TenantModule> all = EnumSet.allOf(TenantModule.class);
        List<Light> lights = List.of(
                new Light("sanmartin", "Farmacia San Martín", "Farmacia San Martín SCS", BusinessType.FARMACIA,
                        TenantPlan.PROFESIONAL, TenantStatus.ACTIVE, null, 0, "Mendoza", "Mendoza",
                        "Av. San Martín 1020", 320, List.of("Sucursal Centro", "Sucursal Godoy Cruz"), all, 'F', 12,
                        "farmaciasanmartin.com.ar", new String[] {"Gabriel Ortega", "Paula Ortega", "Iván Castro"}, 1,
                        List.of()),
                new Light("laabuela", "Almacén La Abuela", "Pereyra Hnos. SH", BusinessType.ALMACEN, TenantPlan.BASICO,
                        TenantStatus.ACTIVE, null, 0, "Mar del Plata", "Buenos Aires", "Rivadavia 2780", 300,
                        List.of("Sucursal Principal"), all, 'A', 14, "almacenlaabuela.com.ar",
                        new String[] {"Carlos Pereyra", "Silvina Pereyra", "Tamara Vega"}, 3, List.of()),
                new Light("24horas", "Kiosco 24 Horas Centro", "Quiroga Damián", BusinessType.KIOSCO,
                        TenantPlan.FREEMIUM, TenantStatus.CANCELLED, "Cerró el local", 118, "Córdoba", "Córdoba",
                        "Bv. San Juan 55", 280, List.of("Sucursal Principal"), EnumSet.of(TenantModule.POS_GONDOLIA),
                        'K', 10, "kiosco24horas.com.ar", new String[] {"Damián Quiroga", "Lorena Quiroga", "Bruno Paz"},
                        120, List.of()),
                new Light("naturalsur", "Dietética Natural Sur", "Natural Sur SRL", BusinessType.DIETETICA,
                        TenantPlan.BASICO, TenantStatus.ACTIVE, null, 0, "Neuquén", "Neuquén", "Av. Argentina 355",
                        250, List.of("Sucursal Principal"), EnumSet.of(TenantModule.POS_INTEGRATION), 'D', 14,
                        "naturalsur.com.ar", new String[] {"Mariana Sosa", "Esteban Sosa", "Camila Rey"}, 2,
                        List.of()),
                new Light("losandes", "Minimercado Los Andes", "Los Andes Comercial SA", BusinessType.MINIMERCADO,
                        TenantPlan.PROFESIONAL, TenantStatus.ACTIVE, null, 0, "Salta", "Salta", "Av. Belgrano 1450",
                        230, List.of("Sucursal Centro", "Sucursal Tres Cerritos"), all, 'M', 15,
                        "minimercadolosandes.com.ar", new String[] {"Jorge Cruz", "Andrea Cruz", "Leandro Flores"}, 1,
                        List.of()),
                new Light("donarosa", "Almacén Doña Rosa", "Villagra Rosa Beatriz", BusinessType.ALMACEN,
                        TenantPlan.BASICO, TenantStatus.ACTIVE, null, 0, "San Miguel de Tucumán", "Tucumán",
                        "Congreso 640", 200, List.of("Sucursal Principal"),
                        EnumSet.of(TenantModule.POS_GONDOLIA, TenantModule.POS_INTEGRATION), 'A', 12,
                        "almacendonarosa.com.ar", new String[] {"Rosa Villagra", "Sergio Villagra", "Milagros Ibáñez"},
                        4, List.of(new EventSpec(TenantEventType.PLAN_CHANGED, 60, "FREEMIUM", "BASICO",
                                        "Pasó al plan Básico para conectar su POS"),
                                new EventSpec(TenantEventType.MODULE_ENABLED, 60, "POS_INTEGRATION", "true", null))),
                new Light("delpueblo", "Farmacia del Pueblo", "Del Pueblo SRL", BusinessType.FARMACIA,
                        TenantPlan.BASICO, TenantStatus.ACTIVE, null, 0, "Santa Fe", "Santa Fe", "San Jerónimo 2100",
                        170, List.of("Sucursal Principal"), all, 'F', 10, "farmaciadelpueblo.com.ar",
                        new String[] {"Alejandro Ríos", "Verónica Ríos", "Sol Medina"}, 12, List.of()),
                new Light("eltrebol", "Despensa El Trébol", "Ramírez Omar", BusinessType.ALMACEN,
                        TenantPlan.FREEMIUM, TenantStatus.CANCELLED, "Se pasó a otro sistema de gestión", 9, "Paraná",
                        "Entre Ríos", "Urquiza 910", 140, List.of("Sucursal Principal"),
                        EnumSet.of(TenantModule.POS_GONDOLIA), 'A', 10, "despensaeltrebol.com.ar",
                        new String[] {"Omar Ramírez", "Liliana Ramírez", "Facundo Gil"}, 11, List.of()),
                new Light("estacion", "Maxikiosco Estación", "Estación Bahía SRL", BusinessType.KIOSCO,
                        TenantPlan.BASICO, TenantStatus.ACTIVE, null, 0, "Bahía Blanca", "Buenos Aires",
                        "Av. Cerri 780", 120, List.of("Sucursal Principal"),
                        EnumSet.of(TenantModule.POS_GONDOLIA, TenantModule.POS_INTEGRATION), 'K', 12,
                        "maxikioscoestacion.com.ar", new String[] {"Pablo Herrera", "Gisela Herrera", "Kevin Molina"},
                        2, List.of(new EventSpec(TenantEventType.DISABLED, 50, "ACTIVE", "DISABLED",
                                        "Falta de pago de la cuota de julio"),
                                new EventSpec(TenantEventType.ENABLED, 44, "DISABLED", "ACTIVE",
                                        "Regularizó el pago"))),
                new Light("granodeoro", "Dietética Grano de Oro", "Grano de Oro SAS", BusinessType.DIETETICA,
                        TenantPlan.BASICO, TenantStatus.DISABLED, "Falta de pago de las cuotas de julio y agosto", 6,
                        "Rosario", "Santa Fe", "San Luis 1320", 95, List.of("Sucursal Principal"), all, 'D', 10,
                        "granodeoro.com.ar", new String[] {"Hernán Blanco", "Cecilia Blanco", "Rocío Luna"}, 8,
                        List.of()),
                new Light("loshermanos", "Almacén Los Hermanos", "Benítez Hnos. SH", BusinessType.ALMACEN,
                        TenantPlan.FREEMIUM, TenantStatus.ACTIVE, null, 0, "Resistencia", "Chaco", "Av. Alberdi 450",
                        70, List.of("Sucursal Principal"), EnumSet.of(TenantModule.POS_GONDOLIA), 'A', 10,
                        "almacenloshermanos.com.ar", new String[] {"Ramón Benítez", "Oscar Benítez", "Luz Aguirre"}, 6,
                        List.of()),
                new Light("elpaso", "Kiosco El Paso", "Gutiérrez Mauro", BusinessType.KIOSCO, TenantPlan.FREEMIUM,
                        TenantStatus.ACTIVE, null, 0, "La Plata", "Buenos Aires", "Diagonal 74 n.º 1500", 25,
                        List.of("Sucursal Principal"), EnumSet.of(TenantModule.POS_GONDOLIA), 'K', 10,
                        "kioscoelpaso.com.ar", new String[] {"Mauro Gutiérrez", "Daniela Gutiérrez", "Tobías Sánchez"},
                        1, List.of()),
                new Light("puntofresco", "Minimercado Punto Fresco", "Punto Fresco SRL", BusinessType.MINIMERCADO,
                        TenantPlan.BASICO, TenantStatus.ACTIVE, null, 0, "Corrientes", "Corrientes",
                        "Junín 1180", 12, List.of("Sucursal Principal"), all, 'M', 12, "puntofresco.com.ar",
                        new String[] {"Alicia Romero", "Fernando Romero", "Nahuel Ojeda"}, 0, List.of()));

        List<TenantSpec> specs = new ArrayList<>();
        for (Light light : lights) {
            List<BranchSpec> branches = new ArrayList<>();
            for (int i = 0; i < light.branchNames().size(); i++) {
                String name = light.branchNames().get(i);
                String code = i == 0 ? (name.equals("Sucursal Principal") ? "PRI" : "CEN")
                        : name.replace("Sucursal ", "").substring(0, 3).toUpperCase();
                branches.add(new BranchSpec(code, name, code, i == 0 ? light.street() : "Av. Libertad " + (300 + i * 97),
                        light.city(), light.province(), null, i == 0 ? 0.55 : 0.4, Channel.MANUAL, null, null));
            }
            String first = branches.getFirst().key();
            int login = light.status() == TenantStatus.ACTIVE ? light.loginDaysAgo()
                    : light.statusDaysAgo() + light.loginDaysAgo() % 5 + 1;
            List<UserSpec> users = List.of(
                    new UserSpec("jefe@" + light.domain(), light.people()[0], Role.TENANT_BOSS, List.of(), login + 3),
                    new UserSpec("admin@" + light.domain(), light.people()[1], Role.TENANT_ADMIN, List.of(), login),
                    new UserSpec("empleado@" + light.domain(), light.people()[2], Role.TENANT_EMPLOYEE,
                            List.of(first), login + 1));
            List<EventSpec> events = new ArrayList<>();
            TenantPlan initialPlan = light.extraEvents().stream()
                    .filter(event -> event.type() == TenantEventType.PLAN_CHANGED)
                    .map(event -> TenantPlan.valueOf(event.fromValue()))
                    .findFirst().orElse(light.plan());
            events.add(new EventSpec(TenantEventType.CREATED, light.createdDaysAgo(), null, initialPlan.name(), null));
            for (TenantModule module : light.modules()) {
                boolean enabledLater = light.extraEvents().stream().anyMatch(event ->
                        event.type() == TenantEventType.MODULE_ENABLED && module.name().equals(event.fromValue()));
                if (!enabledLater) {
                    events.add(new EventSpec(TenantEventType.MODULE_ENABLED, light.createdDaysAgo(), module.name(),
                            "true", null));
                }
            }
            events.addAll(light.extraEvents());
            if (light.status() == TenantStatus.CANCELLED) {
                events.add(new EventSpec(TenantEventType.CANCELLED, light.statusDaysAgo(), "ACTIVE", "CANCELLED",
                        light.reason()));
            } else if (light.status() == TenantStatus.DISABLED) {
                events.add(new EventSpec(TenantEventType.DISABLED, light.statusDaysAgo(), "ACTIVE", "DISABLED",
                        light.reason()));
            }
            specs.add(new TenantSpec(light.key(), light.name(), light.legalName(), null, light.type(), light.plan(),
                    light.status(), light.reason(), light.statusDaysAgo(), light.people()[1],
                    "admin@" + light.domain(), null, light.street(), light.city(), light.province(), null,
                    StockRotation.FIFO, light.modules(), light.createdDaysAgo(), branches, users, light.tag(),
                    light.products(), List.of("aceite-oliva", "salsa-bbq", "colageno", "sopa-tomate", "dulce-leche"),
                    false, events));
        }
        return specs;
    }
}
