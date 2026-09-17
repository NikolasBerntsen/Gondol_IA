package com.gondolia.ai.dto;

import com.gondolia.domain.ai.SalesPattern;
import java.time.LocalDate;
import java.util.List;

/** Análisis de un producto en la sucursal ({@code products[]} de la respuesta de §8.2). */
public record ProductAnalysis(
        Long productId,
        SalesPattern pattern,
        String patternDescription,
        String abcClass,
        String xyzClass,
        Double avgDailySales,
        Double trendPct,
        List<Double> weekdayProfile,
        String forecastMethod,
        List<ForecastPoint> forecast,
        Double daysOfCover,
        LocalDate predictedStockoutDate,
        Integer reorderPoint,
        Integer safetyStock,
        Integer suggestedOrderQty,
        List<Anomaly> anomalies,
        List<LotRisk> lotRisks) {

    public ProductAnalysis {
        weekdayProfile = weekdayProfile == null ? List.of() : weekdayProfile;
        forecast = forecast == null ? List.of() : List.copyOf(forecast);
        anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
        lotRisks = lotRisks == null ? List.of() : List.copyOf(lotRisks);
    }
}
