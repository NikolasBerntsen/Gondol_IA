package com.gondolia.seed;

import com.gondolia.domain.user.Role;
import com.gondolia.seed.DemoWorld.BranchSpec;
import com.gondolia.seed.DemoWorld.TenantSpec;
import com.gondolia.seed.DemoWorld.UserSpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Corre la simulación de los comercios demo en memoria (sin base), con ids inventados, para las pruebas del
 * simulador y de los escenarios.
 */
final class SimulationFixture {

    static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    /** Resultado: la salida de la simulación y la corrida de cada comercio por clave. */
    record Simulation(SimOutput out, Map<String, StoreSimulator.TenantRun> runs) {

        StoreSimulator.TenantRun run(String tenantKey) {
            return runs.get(tenantKey);
        }

        StoreSimulator.BranchRun branch(String tenantKey, String branchKey) {
            return run(tenantKey).branches.stream().filter(branch -> branch.spec.key().equals(branchKey))
                    .findFirst().orElseThrow();
        }

        SimModel.Product product(String tenantKey, String productKey) {
            return run(tenantKey).products.stream().filter(product -> product.template.key().equals(productKey))
                    .findFirst().orElseThrow();
        }

        /** Stock vendible al final (activo, no vencido hoy, no en cuarentena) de un producto en una sucursal. */
        int sellable(StoreSimulator.BranchRun branch, SimModel.Product product, LocalDate today) {
            return out.lots.stream().filter(lot -> lot.branchId == branch.id && lot.product == product
                            && !lot.recalled && !lot.discarded
                            && (lot.expiryDate == null || !lot.expiryDate.isBefore(today)))
                    .mapToInt(lot -> lot.quantity).sum();
        }
    }

    private long sequence = 1000;

    private SimulationFixture() {
    }

    /** Simula, en el orden de {@link DemoWorld#tenants()}, los comercios con historia que cumplen {@code which}. */
    static Simulation simulate(LocalDate today, Instant now, Predicate<TenantSpec> which) {
        SimulationFixture fixture = new SimulationFixture();
        SimOutput out = new SimOutput();
        StoreSimulator simulator = new StoreSimulator(ZONE, today, now, out);
        Map<String, StoreSimulator.TenantRun> runs = new LinkedHashMap<>();
        long tenantId = 1;
        for (TenantSpec spec : DemoWorld.tenants()) {
            if (spec.historyDays() <= 0 || !which.test(spec)) {
                continue;
            }
            StoreSimulator.TenantRun run = fixture.run(spec, tenantId++, today, now);
            runs.put(spec.key(), run);
            simulator.simulate(run, StoreSimulator.seedOf(spec.key(), today));
        }
        return new Simulation(out, runs);
    }

    private StoreSimulator.TenantRun run(TenantSpec spec, long tenantId, LocalDate today, Instant now) {
        Map<String, Long> users = new LinkedHashMap<>();
        for (UserSpec user : spec.users()) {
            users.put(user.email(), ++sequence);
        }
        long admin = spec.users().stream().filter(u -> u.role() == Role.TENANT_ADMIN)
                .map(u -> users.get(u.email())).findFirst().orElseThrow();
        List<StoreSimulator.BranchRun> branches = new ArrayList<>();
        for (BranchSpec branch : spec.branches()) {
            long branchId = ++sequence;
            SimModel.Register register1 = null;
            SimModel.Register register2 = null;
            if (branch.channel() == DemoWorld.Channel.POS_GONDOLIA) {
                register1 = register(tenantId, branchId, "Caja 1", now);
                if (branch.staff().secondRegister() != null) {
                    register2 = register(tenantId, branchId, "Caja 2", now);
                }
            }
            long receiver = spec.users().stream()
                    .filter(u -> u.role() == Role.TENANT_EMPLOYEE && u.branchKeys().contains(branch.key()))
                    .map(u -> users.get(u.email())).findFirst().orElse(admin);
            branches.add(new StoreSimulator.BranchRun(branch, branchId, register1, register2, receiver,
                    List.copyOf(users.values()), spec.key().equals(DemoWorld.EL_SOL) && branch.key().equals("CEN")));
        }
        List<SimModel.Product> products = new ArrayList<>();
        for (DemoCatalog.Template template : DemoWorldBuilder.templatesFor(spec)) {
            products.add(new SimModel.Product(++sequence, tenantId, template, 1,
                    DemoCatalog.supplier(template.supplierKey()).leadTimeDays()));
        }
        boolean elSol = spec.key().equals(DemoWorld.EL_SOL);
        Instant imported = elSol ? today.minusDays(182).atTime(10, 31).atZone(ZONE).toInstant() : null;
        return new StoreSimulator.TenantRun(spec, tenantId, branches, products, admin, users, imported,
                elSol ? 77L : null, 99L, DemoScenarios.forTenant(spec.key()));
    }

    private SimModel.Register register(long tenantId, long branchId, String name, Instant created) {
        long id = ++sequence;
        SimModel.Register register = new SimModel.Register((int) id, tenantId, branchId, name, created);
        register.id = id;
        return register;
    }
}
