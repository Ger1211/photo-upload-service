package com.wedding.upload.replication;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Replication settings. When {@code enabled} is false (the default) the service
 * behaves exactly as before: a single local storage, no peer, no internal API.
 */
@ConfigurationProperties(prefix = "replication")
public class ReplicationProperties {

    private boolean enabled = false;

    /** Label only: "primary" on the cloud VM, "replica" on the home PC. */
    private String role = "primary";

    /** Base URL of the peer, reachable over the tailnet. */
    private String peerUrl = "";

    /** Shared secret required by every /api/internal endpoint. */
    private String token = "";

    /** Max time to wait for a single push before queueing it for retry. */
    private int timeoutMs = 3000;

    /** How often the reconciliation job runs. */
    private long reconcileIntervalMs = 60_000;

    /** Delay before the first reconciliation after startup. */
    private long reconcileInitialDelayMs = 15_000;

    /** Directory for replication state (outbox). Must be outside storage.local.path. */
    private String statePath = "./data";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getPeerUrl() {
        return peerUrl;
    }

    public void setPeerUrl(String peerUrl) {
        this.peerUrl = peerUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getReconcileIntervalMs() {
        return reconcileIntervalMs;
    }

    public void setReconcileIntervalMs(long reconcileIntervalMs) {
        this.reconcileIntervalMs = reconcileIntervalMs;
    }

    public long getReconcileInitialDelayMs() {
        return reconcileInitialDelayMs;
    }

    public void setReconcileInitialDelayMs(long reconcileInitialDelayMs) {
        this.reconcileInitialDelayMs = reconcileInitialDelayMs;
    }

    public String getStatePath() {
        return statePath;
    }

    public void setStatePath(String statePath) {
        this.statePath = statePath;
    }
}
