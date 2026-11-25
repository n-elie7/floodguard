package org.floodguard.dto;

import java.util.Map;

public record PushRegistration(String sectorId, String endpoint, Map<String, String> keys) {}
