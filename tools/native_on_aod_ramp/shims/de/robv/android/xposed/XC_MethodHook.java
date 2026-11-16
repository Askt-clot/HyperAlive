package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {
    public static final int PRIORITY_DEFAULT = 50;

    public void dispatchBefore(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    public void dispatchAfter(MethodHookParam param) throws Throwable {
        afterHookedMethod(param);
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public boolean returnEarly;
        public Object result;
        public Throwable throwable;

        public Object getResult() { return result; }
        public void setResult(Object r) { this.result = r; this.returnEarly = true; }
        public Throwable getThrowable() { return throwable; }
        public void setThrowable(Throwable t) { this.throwable = t; this.returnEarly = true; }
        public boolean hasThrowable() { return throwable != null; }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
        public Object getObjectExtra(String key) { return null; }
        public void setObjectExtra(String key, Object extra) {}
    }

    public final class Unhook {
        public Member getHookedMethod() { return null; }
        public void unhook() {}
    }
}
