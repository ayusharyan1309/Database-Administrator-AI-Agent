package com.optiq.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Key-value settings store persisted in the database.
 * Allows runtime configuration of DB connections, LLM provider, and Slack webhooks
 * without restarting the application.
 */
@Entity
@Table(name = "app_settings")
public class AppSetting {

    @Id
    @Column(length = 100)
    private String key;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String value;

    /** Human-readable description of this setting. */
    @Column(length = 500)
    private String description;

    /** Whether this setting contains sensitive data (masked in UI). */
    @Column(nullable = false)
    private boolean sensitive = false;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected AppSetting() {}

    public AppSetting(String key, String value, String description, boolean sensitive) {
        this.key = key;
        this.value = value;
        this.description = description;
        this.sensitive = sensitive;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isSensitive() { return sensitive; }
    public void setSensitive(boolean sensitive) { this.sensitive = sensitive; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void markUpdated() { this.updatedAt = Instant.now(); }
}
