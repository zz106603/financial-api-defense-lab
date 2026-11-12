package lab.financialapi.identity;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Partner(UUID id, String authSubject, String name, OffsetDateTime createdAt) { }
