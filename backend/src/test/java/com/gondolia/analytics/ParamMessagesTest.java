package com.gondolia.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.alerts.AlertController;
import com.gondolia.insights.InsightsController;
import com.gondolia.insights.RecommendationController;
import com.gondolia.insights.RecommendationController.AcceptBody;
import com.gondolia.insights.RecommendationController.RestockBody;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Los parámetros validados del módulo B responden en castellano: sin {@code message} explícito, Hibernate Validator
 * devuelve su texto en inglés ("must be greater than or equal to 7") dentro de {@code fieldErrors}.
 */
class ParamMessagesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void outOfRangeQueryParametersAnswerInSpanish() throws Exception {
        Method overview = StatisticsController.class.getMethod("overview", int.class);
        assertThat(messages(new StatisticsController(null, null), overview, 6))
                .containsExactly("tiene que ser al menos 7");
        assertThat(messages(new StatisticsController(null, null), overview, 400))
                .containsExactly("no puede ser mayor a 365");

        Method trend = DashboardController.class.getMethod("salesStockTrend", int.class);
        assertThat(messages(new DashboardController(null, null), trend, 0))
                .containsExactly("tiene que ser al menos 1");
    }

    @Test
    void bodiesAnswerInSpanish() {
        Set<ConstraintViolation<AcceptBody>> accept = validator.validate(new AcceptBody("x".repeat(301), 0, 101));
        assertThat(accept).extracting(ConstraintViolation::getMessage).containsExactlyInAnyOrder(
                "admite hasta 300 caracteres", "tiene que ser al menos 1", "no puede ser mayor a 100");

        Set<ConstraintViolation<RestockBody>> restock = validator.validate(new RestockBody(null, 1L, 0, null));
        assertThat(restock).extracting(ConstraintViolation::getMessage)
                .containsExactlyInAnyOrder("es obligatoria", "tiene que ser al menos 1");
    }

    @Test
    void everyRangeConstraintOfTheModuleHasItsOwnMessage() {
        List<String> missing = new ArrayList<>();
        for (Class<?> controller : List.of(DashboardController.class, StatisticsController.class,
                AlertController.class, InsightsController.class, RecommendationController.class)) {
            for (Method method : controller.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    check(controller.getSimpleName() + "." + method.getName() + "(" + parameter.getName() + ")",
                            parameter.getAnnotations(), missing);
                }
            }
            for (Class<?> nested : controller.getDeclaredClasses()) {
                if (!nested.isRecord()) {
                    continue;
                }
                // Las anotaciones de un componente de record quedan en el parámetro del constructor canónico.
                RecordComponent[] components = nested.getRecordComponents();
                Class<?>[] types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
                Parameter[] parameters = canonical(nested, types).getParameters();
                for (int i = 0; i < components.length; i++) {
                    check(nested.getSimpleName() + "." + components[i].getName(), parameters[i].getAnnotations(),
                            missing);
                }
            }
        }
        assertThat(missing).as("restricciones sin mensaje en castellano").isEmpty();
    }

    private static Constructor<?> canonical(Class<?> record, Class<?>[] types) {
        try {
            return record.getDeclaredConstructor(types);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void check(String where, Annotation[] annotations, List<String> missing) {
        for (Annotation annotation : annotations) {
            String message = switch (annotation) {
                case Min min -> min.message();
                case Max max -> max.message();
                case Size size -> size.message();
                default -> null;
            };
            if (message != null && message.startsWith("{jakarta.validation")) {
                missing.add(where + " @" + annotation.annotationType().getSimpleName());
            }
        }
    }

    private static <T> List<String> messages(T controller, Method method, Object... args) {
        return validator.forExecutables().validateParameters(controller, method, args).stream()
                .map(ConstraintViolation::getMessage).toList();
    }
}
