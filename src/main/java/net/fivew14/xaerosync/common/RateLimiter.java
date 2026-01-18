package net.fivew14.xaerosync.common;

/**
 * Token bucket rate limiter for controlling upload/download rates.
 */
public class RateLimiter {

    private int maxTokensPerSecond;
    private double tokens;
    private long lastRefillTime;

    /**
     * Create a new rate limiter.
     *
     * @param maxPerSecond Maximum operations per second
     */
    public RateLimiter(int maxPerSecond) {
        this.maxTokensPerSecond = maxPerSecond;
        this.tokens = maxPerSecond; // Start full
        this.lastRefillTime = System.currentTimeMillis();
    }

    /**
     * Try to acquire a token. Returns true if successful.
     */
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /**
     * Check if a token is available without consuming it.
     */
    public synchronized boolean canAcquire() {
        refill();
        return tokens >= 1.0;
    }

    /**
     * Get the current number of available tokens.
     */
    public synchronized double getAvailableTokens() {
        refill();
        return tokens;
    }

    /**
     * Reset the rate limiter to full capacity.
     */
    public synchronized void reset() {
        this.tokens = maxTokensPerSecond;
        this.lastRefillTime = System.currentTimeMillis();
    }

    /**
     * Update the max tokens per second (for config changes).
     * Adjusts current tokens proportionally if increasing the rate.
     */
    public synchronized void setMaxPerSecond(int maxPerSecond) {
        if (maxPerSecond <= 0) {
            throw new IllegalArgumentException("maxPerSecond must be positive");
        }
        double ratio = (double) maxPerSecond / this.maxTokensPerSecond;
        this.tokens = Math.min(maxPerSecond, this.tokens * ratio);
        this.maxTokensPerSecond = maxPerSecond;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTime;

        if (elapsed > 0) {
            double tokensToAdd = (elapsed / 1000.0) * maxTokensPerSecond;
            tokens = Math.min(maxTokensPerSecond, tokens + tokensToAdd);
            lastRefillTime = now;
        }
    }

    /**
     * Get the configured maximum tokens per second.
     */
    public int getMaxPerSecond() {
        return maxTokensPerSecond;
    }
}
