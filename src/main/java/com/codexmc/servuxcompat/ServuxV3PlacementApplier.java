package com.codexmc.servuxcompat;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

final class ServuxV3PlacementApplier {
    private static final Set<String> WHITELISTED_PROPERTIES = Set.of(
            "inverted",
            "open",
            "attachment",
            "axis",
            "half",
            "face",
            "type",
            "mode",
            "hinge",
            "facing",
            "orientation",
            "shape",
            "pose",
            "bites",
            "delay",
            "note",
            "rotation"
    );

    private static final Set<String> BLACKLISTED_PROPERTIES = Set.of(
            "waterlogged",
            "powered"
    );

    private static final List<String> DIRECTION_3D_ORDER = List.of(
            "down", "up", "north", "south", "west", "east"
    );

    private ServuxV3PlacementApplier() {
    }

    static boolean apply(Block block, int protocolValue, Logger logger, boolean debug) {
        BlockData currentData = block.getBlockData();
        ParsedBlockData parsed = ParsedBlockData.parse(currentData.getAsString(false));
        if (parsed.properties().isEmpty()) {
            return false;
        }

        Optional<List<PropertyInfo>> reflectedProperties = readRuntimeProperties(currentData, logger, debug);
        List<PropertyInfo> properties = reflectedProperties.orElseGet(() -> fallbackProperties(parsed));
        if (properties.isEmpty()) {
            return false;
        }

        LinkedHashMap<String, String> updated = new LinkedHashMap<>(parsed.properties());
        PropertyInfo directionProperty = firstDirectionProperty(properties);

        if (directionProperty != null) {
            String desired = decodeDirection(protocolValue, directionProperty.currentValue());
            if (desired != null && directionProperty.possibleValues().contains(desired)) {
                updated.put(directionProperty.name(), desired);
            }
            protocolValue >>>= 3;
        }

        protocolValue >>>= 1;

        List<PropertyInfo> sorted = new ArrayList<>(properties);
        sorted.sort(Comparator.comparing(PropertyInfo::name));

        for (PropertyInfo property : sorted) {
            if (directionProperty != null && directionProperty.name().equals(property.name())) {
                continue;
            }
            if (!WHITELISTED_PROPERTIES.contains(property.name())
                    || BLACKLISTED_PROPERTIES.contains(property.name())) {
                continue;
            }

            List<String> values = property.possibleValues();
            if (values.isEmpty()) {
                continue;
            }

            int requiredBits = requiredBits(values.size());
            int bitMask = ~(0xFFFFFFFF << requiredBits);
            int valueIndex = protocolValue & bitMask;
            if (valueIndex >= 0 && valueIndex < values.size()) {
                String value = values.get(valueIndex);
                if (!("type".equals(property.name()) && "double".equals(value))) {
                    updated.put(property.name(), value);
                }
                protocolValue >>>= requiredBits;
            }
        }

        String outputString = parsed.toBlockDataString(updated);
        String inputString = currentData.getAsString(false);
        if (inputString.equals(outputString)) {
            return false;
        }

        try {
            BlockData newData = Bukkit.createBlockData(outputString);
            if (!newData.getMaterial().equals(currentData.getMaterial())) {
                debug(logger, debug, "Skipping v3 apply because material changed while parsing: " + outputString);
                return false;
            }

            block.setBlockData(newData, false);
            return true;
        } catch (RuntimeException ex) {
            debug(logger, debug, "Failed to apply v3 block data '" + outputString + "': " + ex.getMessage());
            return false;
        }
    }

    private static PropertyInfo firstDirectionProperty(List<PropertyInfo> properties) {
        for (PropertyInfo property : properties) {
            if (property.isDirection() && !"vertical_direction".equals(property.name())) {
                return property;
            }
        }

        return null;
    }

    private static String decodeDirection(int protocolValue, String current) {
        int decodedFacingIndex = (protocolValue & 0xF) >> 1;
        if (decodedFacingIndex == 6) {
            return switch (current) {
                case "down" -> "up";
                case "up" -> "down";
                case "north" -> "south";
                case "south" -> "north";
                case "west" -> "east";
                case "east" -> "west";
                default -> null;
            };
        }

        if (decodedFacingIndex >= 0 && decodedFacingIndex < DIRECTION_3D_ORDER.size()) {
            return DIRECTION_3D_ORDER.get(decodedFacingIndex);
        }

        return null;
    }

    private static int requiredBits(int size) {
        int power = 1;
        int bits = 0;

        while (power < size) {
            power <<= 1;
            bits++;
        }

        return bits;
    }

    private static Optional<List<PropertyInfo>> readRuntimeProperties(BlockData blockData, Logger logger, boolean debug) {
        try {
            Object state = invokeNoArg(blockData, "getState");
            if (state == null) {
                return Optional.empty();
            }

            Object rawProperties = invokeNoArg(state, "getProperties");
            if (!(rawProperties instanceof Collection<?> propertyCollection)) {
                return Optional.empty();
            }

            List<PropertyInfo> properties = new ArrayList<>();
            for (Object property : propertyCollection) {
                String name = String.valueOf(invokeNoArg(property, "getName"));
                Object valueClass = invokeNoArg(property, "getValueClass");
                String valueClassName = valueClass instanceof Class<?> clazz ? clazz.getName() : "";
                Object currentValue = invokeOneArg(state, "getValue", property);
                String currentValueName = propertyValueName(property, currentValue);
                Object rawValues = invokeNoArg(property, "getPossibleValues");
                if (!(rawValues instanceof Collection<?> valuesCollection)) {
                    continue;
                }

                List<Object> values = new ArrayList<>(valuesCollection);
                values.sort((left, right) -> ((Comparable<Object>) left).compareTo(right));

                List<String> valueNames = new ArrayList<>();
                for (Object value : values) {
                    valueNames.add(propertyValueName(property, value));
                }

                properties.add(new PropertyInfo(name, currentValueName, valueNames,
                        valueClassName.endsWith(".Direction")));
            }

            return Optional.of(properties);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            debug(logger, debug, "Falling back to Bukkit block-data parser: " + ex.getMessage());
            return Optional.empty();
        }
    }

    private static List<PropertyInfo> fallbackProperties(ParsedBlockData parsed) {
        List<PropertyInfo> properties = new ArrayList<>();

        for (Map.Entry<String, String> entry : parsed.properties().entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            List<String> values = fallbackValues(name, value);
            boolean direction = "facing".equals(name) && values.contains("north");
            properties.add(new PropertyInfo(name, value, values, direction));
        }

        return properties;
    }

    private static List<String> fallbackValues(String name, String currentValue) {
        return switch (name) {
            case "facing" -> List.of("down", "up", "north", "south", "west", "east");
            case "axis" -> List.of("x", "y", "z");
            case "half" -> List.of("top", "bottom");
            case "face" -> List.of("floor", "wall", "ceiling");
            case "type" -> {
                if ("left".equals(currentValue) || "right".equals(currentValue) || "single".equals(currentValue)) {
                    yield List.of("single", "left", "right");
                }
                yield List.of("top", "bottom", "double");
            }
            case "mode" -> List.of("compare", "subtract");
            case "hinge" -> List.of("left", "right");
            case "shape" -> {
                if ("ascending_east".equals(currentValue) || "north_south".equals(currentValue)) {
                    yield List.of("north_south", "east_west", "ascending_east", "ascending_west",
                            "ascending_north", "ascending_south", "south_east", "south_west",
                            "north_west", "north_east");
                }
                yield List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right");
            }
            case "orientation" -> List.of("down_east", "down_north", "down_south", "down_west",
                    "up_east", "up_north", "up_south", "up_west",
                    "west_up", "east_up", "north_up", "south_up");
            case "attachment" -> List.of("floor", "ceiling", "single_wall", "double_wall");
            case "pose" -> List.of("standing", "sitting", "running", "spinning");
            case "open", "inverted" -> List.of("false", "true");
            case "bites" -> intValues(0, 6);
            case "delay" -> intValues(1, 4);
            case "note" -> intValues(0, 24);
            case "rotation" -> intValues(0, 15);
            default -> List.of(currentValue);
        };
    }

    private static List<String> intValues(int min, int max) {
        List<String> values = new ArrayList<>();
        for (int i = min; i <= max; i++) {
            values.add(Integer.toString(i));
        }
        return values;
    }

    private static String propertyValueName(Object property, Object value) throws ReflectiveOperationException {
        Object name = invokeOneArg(property, "getName", value);
        return String.valueOf(name).toLowerCase(Locale.ROOT);
    }

    private static Object invokeNoArg(Object target, String name) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), name, 0);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object invokeOneArg(Object target, String name, Object value) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), name, 1);
        method.setAccessible(true);
        return method.invoke(target, value);
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }

        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }

        throw new NoSuchMethodException(type.getName() + "#" + name + "/" + parameterCount);
    }

    private static void debug(Logger logger, boolean debug, String message) {
        if (debug) {
            logger.info("[debug] " + message);
        }
    }

    private record PropertyInfo(String name, String currentValue, List<String> possibleValues, boolean isDirection) {
    }

    private record ParsedBlockData(String id, LinkedHashMap<String, String> properties) {
        static ParsedBlockData parse(String blockDataString) {
            int bracket = blockDataString.indexOf('[');
            if (bracket < 0) {
                return new ParsedBlockData(blockDataString, new LinkedHashMap<>());
            }

            String id = blockDataString.substring(0, bracket);
            String propertyString = blockDataString.substring(bracket + 1, blockDataString.length() - 1);
            LinkedHashMap<String, String> properties = new LinkedHashMap<>();

            if (!propertyString.isBlank()) {
                for (String part : propertyString.split(",")) {
                    int equals = part.indexOf('=');
                    if (equals > 0 && equals < part.length() - 1) {
                        properties.put(part.substring(0, equals), part.substring(equals + 1));
                    }
                }
            }

            return new ParsedBlockData(id, properties);
        }

        String toBlockDataString(Map<String, String> newProperties) {
            if (newProperties.isEmpty()) {
                return id;
            }

            StringBuilder builder = new StringBuilder(id).append('[');
            boolean first = true;
            for (Map.Entry<String, String> entry : newProperties.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                builder.append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
            return builder.append(']').toString();
        }
    }
}
