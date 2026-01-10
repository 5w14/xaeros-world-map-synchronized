package net.fivew14.xaerosync.common;

/**
 * Utility for validating and sanitizing timestamps in sync packets.
 * Prevents clients from submitting future timestamps to gain priority.
 */
public final class TimestampValidator {

    private TimestampValidator() {
    } // Utility class

    /**
     * Maximum allowed clock drift into the future (5 minutes).
     * Allows for minor clock synchronization issues.
     */
    private static final long MAX_FUTURE_DRIFT_MS = 5 * 60 * 1000L;

    /**
     * Minimum valid timestamp (Minecraft 1.0 release: November 18, 2011).
     * Any timestamp before this is clearly invalid.
     */
    private static final long MIN_VALID_TIMESTAMP = 1321574400000L;

    /**
     * Check if a timestamp is valid.
     *
     * @param timestamp The timestamp to validate (milliseconds since epoch)
     * @return true if the timestamp is within acceptable bounds
     */
    public static boolean isValid(long timestamp) {
        long now = System.currentTimeMillis();

        // Reject timestamps too far in the future
        if (timestamp > now + MAX_FUTURE_DRIFT_MS) {
            return false;
        }

        // Reject timestamps before Minecraft existed
        return timestamp >= MIN_VALID_TIMESTAMP;
    }

    /**
     * Sanitize a timestamp by clamping it to valid bounds.
     * If the timestamp is in the future, returns current time.
     * If the timestamp is before Minecraft, returns minimum valid time.
     *
     * @param timestamp The timestamp to sanitize
     * @return A valid timestamp
     */
    public static long sanitize(long timestamp) {
        long now = System.currentTimeMillis();

        // Clamp future timestamps to now
        if (timestamp > now) {
            return now;
        }

        // Clamp ancient timestamps to minimum
        if (timestamp < MIN_VALID_TIMESTAMP) {
            return MIN_VALID_TIMESTAMP;
        }

        return timestamp;
    }

    /**
     * Get the current time as a valid timestamp.
     */
    public static long now() {
        return System.currentTimeMillis();
    }
}
