package lab.financialapi.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Customer(UUID id, String authSubject, String name, OffsetDateTime createdAt) { }
