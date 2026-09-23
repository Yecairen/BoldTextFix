package dev.yecairen.boldtextfix;

import java.lang.reflect.Method;

/** Optional shader-loader bridge; the mod remains independent of Iris at compile and load time. */
public final class ShaderCompatibility {
    private static final String IRIS_API = "net.irisshaders.iris.api.v0.IrisApi";

    private static volatile boolean initialized;
    private static volatile Method getInstance;
    private static volatile Method isShaderPackInUse;

    private ShaderCompatibility() {
    }

    public static boolean isShaderPackInUse() {
        initialize();
        Method instanceMethod = getInstance;
        Method shaderMethod = isShaderPackInUse;
        if (instanceMethod == null || shaderMethod == null) {
            return false;
        }

        try {
            Object api = instanceMethod.invoke(null);
            return Boolean.TRUE.equals(shaderMethod.invoke(api));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static void initialize() {
        if (initialized) {
            return;
        }
        synchronized (ShaderCompatibility.class) {
            if (initialized) {
                return;
            }
            try {
                Class<?> apiClass = Class.forName(IRIS_API, false, ShaderCompatibility.class.getClassLoader());
                getInstance = apiClass.getMethod("getInstance");
                isShaderPackInUse = apiClass.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                getInstance = null;
                isShaderPackInUse = null;
            } finally {
                initialized = true;
            }
        }
    }
}
