package android.os;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Handler {
    public static final class Scheduled {
        public final Runnable runnable;
        public final long runAtMs;

        Scheduled(Runnable runnable, long runAtMs) {
            this.runnable = runnable;
            this.runAtMs = runAtMs;
        }
    }

    private final Looper mLooper;
    public final List<Scheduled> queue = new ArrayList<>();
    public boolean failNextPost = false;
    public boolean failNextPostDelayed = false;
    public boolean throwOnPost = false;
    public boolean throwOnPostDelayed = false;

    public Handler() {
        Looper l = Looper.myLooper();
        this.mLooper = (l != null) ? l : Looper.getMainLooper();
    }

    public Handler(Looper looper) {
        this.mLooper = looper;
    }

    public Looper getLooper() {
        return mLooper;
    }

    public synchronized boolean post(Runnable r) {
        if (throwOnPost) {
            throwOnPost = false;
            throw new RuntimeException("injected post failure");
        }
        if (failNextPost) {
            failNextPost = false;
            return false;
        }
        queue.add(new Scheduled(r, SystemClock.sNowMs));
        return true;
    }

    public synchronized boolean postAtFrontOfQueue(Runnable r) {
        if (throwOnPost) {
            throwOnPost = false;
            throw new RuntimeException("injected postAtFrontOfQueue failure");
        }
        if (failNextPost) {
            failNextPost = false;
            return false;
        }
        queue.add(0, new Scheduled(r, SystemClock.sNowMs));
        return true;
    }

    public synchronized boolean postDelayed(Runnable r, long delayMillis) {
        if (throwOnPostDelayed) {
            throwOnPostDelayed = false;
            throw new RuntimeException("injected postDelayed failure");
        }
        if (failNextPostDelayed) {
            failNextPostDelayed = false;
            return false;
        }
        queue.add(new Scheduled(r, SystemClock.sNowMs + Math.max(0L, delayMillis)));
        return true;
    }

    public synchronized void removeCallbacks(Runnable r) {
        Iterator<Scheduled> it = queue.iterator();
        while (it.hasNext()) {
            if (it.next().runnable == r) {
                it.remove();
            }
        }
    }

    public int runReadyTasks() {
        int ran = 0;
        while (true) {
            Scheduled next = null;
            synchronized (this) {
                for (Scheduled s : queue) {
                    if (s.runAtMs <= SystemClock.sNowMs) {
                        if (next == null || s.runAtMs < next.runAtMs) {
                            next = s;
                        }
                    }
                }
                if (next != null) {
                    queue.remove(next);
                }
            }
            if (next == null) break;
            next.runnable.run();
            ran++;
        }
        return ran;
    }

    public synchronized int pendingCount() {
        return queue.size();
    }
}
