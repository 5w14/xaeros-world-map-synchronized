package net.fivew14.xaerosync.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token bucket rate limiter for controlling upload/download rates.
 * Thread-safe implementation using atomic variables.
 */
public class RateLimiter {

    private volatile int maxTokensPerSecond;
    private final AtomicLong tokens;
    private final long refillIntervalNanos;
    private volatile long lastRefillTime;

    private static final long MAX_REFRESH_INTERVAL_NS = 1_000_000_000L / 60L;

    /**
     * Create a new rate limiter.
     *
     * @param maxPerSecond Maximum operations per second (must be >= 1)
     */
    public RateLimiter(int maxPerSecond) {
        if (maxPerSecond <= 0) {
            throw new IllegalArgumentException("maxPerSecond must be positive");
        }
        this.maxTokensPerSecond = maxPerSecond;
        this.tokens = new AtomicLong(maxPerSecond);
        this.refillIntervalNanos = Math.min(MAX_REFRESH_INTERVAL_NS, 1_000_000_000L / maxPerSecond);
        this.lastRefillTime = System.nanoTime();
    }

    /**
     * Try to acquire a token. Returns true if successful.
     * Refills tokens based on elapsed time before attempting to acquire.
     */
    public boolean tryAcquire() {
        refill();
        long current = tokens.get();
        if (current <= 0) {
            return false;
        }
        return tokens.compareAndSet(current, current - 1);
    }

    /**
     * Check if a token is available without consuming it.
     */
    public boolean canAcquire() {
        refill();
        return tokens.get() > 0;
    }

    /**
     * Get the current number of available tokens.
     */
    public long getAvailableTokens() {
        refill();
        return tokens.get();
    }

    /**
     * Reset the rate limiter to full capacity.
     */
    public void reset() {
        tokens.set(maxTokensPerSecond);
        lastRefillTime = System.nanoTime();
    }

    /**
     * Update the max tokens per second (for config changes).
     * Resets tokens to the new maximum.
     */
    public void setMaxPerSecond(int maxPerSecond) {
        if (maxPerSecond <= 0) {
            throw new IllegalArgumentException("maxPerSecond must be positive");
        }
        this.maxTokensPerSecond = maxPerSecond;
        reset();
    }

    /**
     * Refill tokens based on elapsed time.
     * Called internally before each operation.
     */
    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillTime;
        if (elapsed >= refillIntervalNanos) {
            long intervals = elapsed / refillIntervalNanos;
            tokens.updateAndGet(current -> Math.min(maxTokensPerSecond, current + intervals));
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
