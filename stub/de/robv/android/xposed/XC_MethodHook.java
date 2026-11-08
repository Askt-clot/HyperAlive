package de.robv.android.xposed;

import java.lang.reflect.Member;

/** 编译期桩：字段名/方法名必须与真实 Xposed API 一致，运行时按名字解析。 */
public abstract class XC_MethodHook {

    public static final int PRIORITY_DEFAULT = 50;

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        public Object getResult() {
            return null;
        }

        public void setResult(Object result) {
        }

        public Throwable getThrowable() {
            return null;
        }

        public void setThrowable(Throwable throwable) {
        }

        public boolean hasThrowable() {
            return false;
        }

        public Object getResultOrThrowable() throws Throwable {
            return null;
        }
    }

    public final class Unhook {
        public Member getHookedMethod() {
            return null;
        }

        public void unhook() {
        }
    }
}