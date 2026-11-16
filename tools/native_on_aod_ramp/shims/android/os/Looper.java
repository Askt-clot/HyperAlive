package android.os;

public final class Looper {
    private static final Looper MAIN = new Looper();
    private static final ThreadLocal<Looper> TLS = new ThreadLocal<>();

    public static Looper getMainLooper() {
        return MAIN;
    }

    public static Looper myLooper() {
        return TLS.get();
    }

    public static void setCurrentLooperForTest(Looper l) {
        TLS.set(l);
    }
}
