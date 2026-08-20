package android.os;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * iOS-safe SystemClock implementation. Suwayomi's desktop implementation
 * queries java.management, which is not present in OpenJDK/mobile's compact
 * runtime image.
 */
public final class SystemClock {
    private static final long START_NANOS = System.nanoTime();

    private SystemClock() {
    }

    public static void sleep(long milliseconds) {
        long deadline = System.nanoTime() + milliseconds * 1_000_000L;
        boolean interrupted = false;
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                Thread.sleep(
                    remaining / 1_000_000L,
                    (int) (remaining % 1_000_000L)
                );
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean setCurrentTimeMillis(long milliseconds) {
        return false;
    }

    public static long uptimeMillis() {
        return (System.nanoTime() - START_NANOS) / 1_000_000L;
    }

    public static Clock uptimeMillisClock() {
        return monotonicClock();
    }

    public static Clock uptimeClock() {
        return monotonicClock();
    }

    public static long elapsedRealtime() {
        return uptimeMillis();
    }

    public static Clock elapsedRealtimeClock() {
        return monotonicClock();
    }

    public static long elapsedRealtimeNanos() {
        return System.nanoTime() - START_NANOS;
    }

    public static long currentThreadTimeMillis() {
        return elapsedRealtime();
    }

    public static long currentThreadTimeMicro() {
        return elapsedRealtimeNanos() / 1_000L;
    }

    public static long currentTimeMicro() {
        return System.currentTimeMillis() * 1_000L;
    }

    public static long currentNetworkTimeMillis() {
        return System.currentTimeMillis();
    }

    public static Clock currentNetworkTimeClock() {
        return Clock.systemUTC();
    }

    private static Clock monotonicClock() {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(uptimeMillis());
            }
        };
    }
}
