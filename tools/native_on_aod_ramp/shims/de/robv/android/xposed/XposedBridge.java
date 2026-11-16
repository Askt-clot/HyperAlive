package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class XposedBridge {
    public interface OriginalCall {
        Object run(Object[] args) throws Throwable;
    }

    public static final Map<Member, List<XC_MethodHook>> HOOKS = new HashMap<>();

    private XposedBridge() {}

    public static void log(String text) {}
    public static void log(Throwable t) {}

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        HOOKS.computeIfAbsent(hookMethod, k -> new ArrayList<>()).add(callback);
        return null;
    }

    public static Object dispatch(Class<?> cls, String name, Class<?>[] paramTypes,
                                  Object thisObj, Object[] args, OriginalCall orig) {
        Method m = null;
        try {
            m = cls.getDeclaredMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        List<XC_MethodHook> list = HOOKS.get(m);
        if (list == null || list.isEmpty()) {
            try {
                return orig.run(args);
            } catch (RuntimeException re) {
                throw re;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = m;
        param.thisObject = thisObj;
        param.args = args;

        int executedBefore = 0;
        try {
            for (; executedBefore < list.size(); executedBefore++) {
                list.get(executedBefore).dispatchBefore(param);
                if (param.returnEarly) {
                    executedBefore++;
                    break;
                }
            }
            if (!param.returnEarly) {
                param.result = orig.run(param.args);
            }
            for (int i = executedBefore - 1; i >= 0; i--) {
                list.get(i).dispatchAfter(param);
            }
            if (param.throwable != null) {
                throw new RuntimeException(param.throwable);
            }
            return param.result;
        } catch (RuntimeException re) {
            throw re;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
