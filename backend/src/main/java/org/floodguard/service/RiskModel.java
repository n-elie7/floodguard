package org.floodguard.service;

import org.springframework.stereotype.Component;

@Component
public class RiskModel {
  // Experimental, explainable index. These normalizers require validation against local events.
  public int score(double rain12hMm, double soilMoisture, double baselineDailyMm) {
    double rainfall = clamp(rain12hMm / 60.0);
    double soil = clamp(soilMoisture / 0.45);
    double deviation = clamp((rain12hMm / Math.max(2.0, baselineDailyMm) - 1.0) / 3.0);
    return (int) Math.round(100 * (0.5 * rainfall + 0.3 * soil + 0.2 * deviation));
  }

  public String level(int score, int moderate, int high) {
    return score >= high ? "HIGH" : score >= moderate ? "MODERATE" : "LOW";
  }

  private double clamp(double value) {
    return Math.max(0, Math.min(1, value));
  }
}
