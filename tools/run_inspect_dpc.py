import sys
import os
sys.path.append(os.path.abspath('qihang_lsp'))
import deploy

code = r"""
package com.qihang.aod.root;

import java.lang.reflect.Method;
import java.lang.reflect.Field;

public class InspectDpc {
    public static void main(String[] args) {
        try {
            Class<?> c = Class.forName("com.android.server.display.DisplayPowerControllerImpl");
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("animat") || m.getName().toLowerCase().contains("wait") || m.getName().toLowerCase().contains("off")) {
                    System.out.println("Method: " + m.getName() + " params=" + java.util.Arrays.toString(m.getParameterTypes()));
                }
            }
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().toLowerCase().contains("animat") || f.getName().toLowerCase().contains("wait") || f.getName().toLowerCase().contains("off")) {
                    System.out.println("Field: " + f.getName() + " type=" + f.getType().getName());
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
"""

with open('qihang_lsp/module/src/com/qihang/aod/root/InspectDpc.java', 'w', encoding='utf-8') as f:
    f.write(code)

deploy.build()
deploy.push_dex()
print(deploy.app_process('com.qihang.aod.root.InspectDpc'))
