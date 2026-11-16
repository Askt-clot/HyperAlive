package android.os;

public final class SystemClock {
    public static long sNowMs = 10000L;

    public static long uptimeMillis() {
        return sNowMs;
    }

    public static long elapsedRealtime() {
        return sNowMs + 50000L;
    }
}
