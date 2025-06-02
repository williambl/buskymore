package com.williambl.buskymore;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

// bini (buskymore ini)
public class Bini {
    public <T> T parse(Class<T> clazz, List<String> lines) {
        if (this.isAtomic(clazz)) {
            return (T) this.parseAtomic(clazz, lines.getFirst());
        } else {
            try {
                return this.parseComposite(clazz, lines, 0, null, null);
            } catch (InvocationTargetException | IllegalAccessException | InstantiationException e) {
                throw new RuntimeException("Failed to parse Bini value %s".formatted(String.join("\n", lines)), e);
            }
        }
    }

    public String unparse(Object value) {
        StringBuilder res = new StringBuilder(512);
        if (this.isAtomic(value.getClass())) {
            this.unparseAtomicValue(value, res);
        } else {
            try {
                this.unparseCompositeValue(value, res, 0, null);
            } catch (InvocationTargetException | IllegalAccessException e) {
                throw new RuntimeException("Failed to unparse Bini value %s".formatted(value), e);
            }
        }
        return res.toString();
    }

    private void unparseAtomicValue(Object value, StringBuilder sb) {
        switch (value) {
            case Number n -> sb.append(n);
            case String s -> {
                s.codePoints().forEachOrdered(codepoint -> {
                    if (codepoint == '\"') {
                        sb.append("\"");
                    } else if (codepoint == '\\') {
                        sb.append("\\\\");
                    } else if (codepoint == '\b') {
                        sb.append("\\b");
                    } else if (codepoint == '\f') {
                        sb.append("\\f");
                    } else if (codepoint == '\n') {
                        sb.append("\\n");
                    } else if (codepoint == '\r') {
                        sb.append("\\r");
                    } else if (codepoint == '\t') {
                        sb.append("\\t");
                    } else if (Character.isISOControl(codepoint) || !Character.isDefined(codepoint)) {
                        sb.append("\\u").append("%04x".formatted(codepoint).toUpperCase(Locale.ROOT));
                    } else {
                        sb.appendCodePoint(codepoint);
                    }
                });
            }
            default -> sb.append(value);
        }
    }

    private void unparseCompositeValue(Object obj, StringBuilder sb, int indent, RecordComponent toIgnore) throws InvocationTargetException, IllegalAccessException {
        switch (obj) {
            case List<?> list -> this.unparseList(list, sb, indent);
            case Record record -> this.unparseRecord(record, sb, indent, toIgnore);
            default -> throw new UnsupportedOperationException("I don't know how to unparse %s :(".formatted(obj.getClass()));
        }
    }

    private void unparseList(List<?> list, StringBuilder sb, int indent) throws InvocationTargetException, IllegalAccessException {
        for (int i = 0; i < list.size(); i++) {
            var value = list.get(i);
            sb.append("\t".repeat(indent));
            sb.append('[');
            RecordComponent nameCmp = null;
            var clazz = value.getClass();
            if (clazz.isRecord()) {
                for (var cmp : clazz.getRecordComponents()) {
                    if (cmp.getName().equals("name")) {
                        sb.append(cmp.getAccessor().invoke(value));
                        nameCmp = cmp;
                        break;
                    }
                }
            }
            sb.append(']');
            if (this.isAtomic(value.getClass())) {
                sb.append(' ');
                this.unparseAtomicValue(value, sb);
            } else {
                sb.append('\n');
                this.unparseCompositeValue(value, sb, indent + 1, nameCmp);
            }
            if (i < list.size() - 1) {
                sb.append('\n');
            }
        }
    }

    private void unparseRecord(Record record, StringBuilder sb, int indent, RecordComponent toIgnore) throws InvocationTargetException, IllegalAccessException {
        var clazz = record.getClass();
        RecordComponent[] components = clazz.getRecordComponents();
        String[] componentNames = new String[components.length];
        Object[] componentValues = new Object[components.length];
        int longestAtomicCmpName = 0;
        for (int i = 0; i < components.length; i++) {
            RecordComponent cmp = components[i];
            if (toIgnore != null && cmp.getName().equals(toIgnore.getName())) {
                continue;
            }
            String name = getName(cmp);
            componentNames[i] = name;
            var value = cmp.getAccessor().invoke(record);
            componentValues[i] = value;
            if (this.isAtomic(value.getClass())) {
                longestAtomicCmpName = Math.max(
                        longestAtomicCmpName,
                        name.length());
            }
        }
        for (int i = 0; i < components.length; i++) {
            var cmp = components[i];
            if (toIgnore != null && cmp.getName().equals(toIgnore.getName())) {
                continue;
            }
            String name = componentNames[i];
            var value = componentValues[i];
            sb.append("\t".repeat(indent));
            sb.append(name);
            if (this.isAtomic(value.getClass())) {
                sb.append(" ".repeat(longestAtomicCmpName + 1 - name.length()));
                sb.append("= ");
                this.unparseAtomicValue(value, sb);
            } else {
                sb.append(':');
                sb.append('\n');
                this.unparseCompositeValue(value, sb, indent+1, null);
            }
            if (i < components.length - 1) {
                sb.append('\n');
            }
        }
    }

    private boolean isAtomic(Class<?> clazz) {
        return String.class.isAssignableFrom(clazz)
        || Integer.class.isAssignableFrom(clazz)|| int.class.isAssignableFrom(clazz)
        || Long.class.isAssignableFrom(clazz)|| long.class.isAssignableFrom(clazz)
        || Float.class.isAssignableFrom(clazz)|| float.class.isAssignableFrom(clazz)
        || Double.class.isAssignableFrom(clazz)|| double.class.isAssignableFrom(clazz)
        || Boolean.class.isAssignableFrom(clazz)|| boolean.class.isAssignableFrom(clazz)
        || PostFilter.Fisp.class.isAssignableFrom(clazz);
    }

    private <T> T parseRecord(List<String> lines, Class<T> clazz, int indent, String nameCmpName, String nameValue) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        RecordComponent[] cmps = clazz.getRecordComponents();
        String[] cmpNames = new String[cmps.length];
        boolean[] cmpAtomicness = new boolean[cmps.length];
        Object[] values = new Object[cmps.length];
        for (int i = 0; i < cmps.length; i++) {
            var cmp = cmps[i];
            var name = getName(cmp);
            cmpNames[i] = name;
            cmpAtomicness[i] = this.isAtomic(cmp.getType());
            if (name.equals(nameCmpName)) {
                values[i] = nameValue;
            }
        }
        // this is done up here so we're not re-allocating it every line
        String[] possibleFields = new String[cmps.length];
        boolean hasFoundFirstActualLine = false;
        lineLoop:
        for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
            var line = lines.get(lineIdx);
            if (line.isBlank()) {
                continue;
            }
            int foundIndent = 0;
            int fieldStartedAt = -1;
            int foundFieldIdx = -1;
            int fieldValueStartedAt = -1;
            charLoop:
            for (int charIdx = 0; charIdx < line.length(); charIdx++) {
                char charAt = line.charAt(charIdx);
                // scenario 1: still going through indentation
                if (fieldStartedAt < 0 && (charAt == '\t' || charAt == ' ')) {
                    foundIndent += (charAt == '\t' ? 4 : 1);
                    continue;
                }
                // scenario 2: found the end of indentation, but havent started reading field yet
                if (fieldStartedAt < 0) {
                    if (foundIndent < indent) {
                        break lineLoop;
                    }
                    if (foundIndent > indent && hasFoundFirstActualLine) {
                        continue lineLoop;
                    }
                    // if the first line with content is more indented than expected, then we just change the expected indent to match
                    if (!hasFoundFirstActualLine) {
                        indent = foundIndent;
                        hasFoundFirstActualLine = true;
                    }
                    fieldStartedAt = charIdx;
                    System.arraycopy(cmpNames, 0, possibleFields, 0, cmpNames.length);
                }
                // scenario 3: we've found the start of the field name, but haven't worked out which field it is yet
                if (foundFieldIdx < 0) {
                    boolean anyFieldIsPossible = false;
                    for (int fieldIdx = 0; fieldIdx < possibleFields.length; fieldIdx++) {
                        String fieldName = possibleFields[fieldIdx];
                        if (fieldName == null) {
                            continue;
                        }
                        anyFieldIsPossible = true;
                        int fieldNameCharIdx = charIdx - fieldStartedAt;
                        if (fieldNameCharIdx >= fieldName.length()) {
                            if (Character.isWhitespace(charAt)) {
                                continue;
                            } else if ((cmpAtomicness[fieldIdx] && charAt == '=') || (!cmpAtomicness[fieldIdx] && charAt == ':')) {
                                if (!cmpAtomicness[fieldIdx]) {
                                    // for non-atomics, we count the rest of the line as an entire initial for the non-atomic parser,
                                    // so we use this value to split on it.
                                    // (for atomics, we don't count the field value as starting until we've gone through all the whitespace)
                                    fieldValueStartedAt = charIdx + 1;
                                }
                                foundFieldIdx = fieldIdx;
                                continue charLoop;
                            } else {
                                possibleFields[fieldIdx] = null;
                                continue;
                            }
                        }
                        if (fieldName.charAt(fieldNameCharIdx) != charAt) {
                            possibleFields[fieldIdx] = null;
                        }
                    }
                    if (!anyFieldIsPossible) {
                        continue lineLoop;
                    }
                }
                // scenario 4: we've found a field and matched it and it's atomic, but we haven't found its actual start
                if (foundFieldIdx >= 0 && fieldValueStartedAt < 0) {
                    if (Character.isWhitespace(charAt)) {
                        continue;
                    } else {
                        fieldValueStartedAt = charIdx;
                        break;
                    }
                }
            }

            // we don't know what this field is, ignore it
            if (foundFieldIdx < 0) {
                continue;
            }

            // okay we got the field yay lets actually parse it
            if (cmpAtomicness[foundFieldIdx]) {
                values[foundFieldIdx] = this.parseAtomic(cmps[foundFieldIdx].getGenericType(), line.substring(fieldValueStartedAt));
            } else {
                lines.set(lineIdx, line.substring(fieldValueStartedAt));
                List<String> subList = lines.subList(lineIdx, lines.size());
                values[foundFieldIdx] = this.parseComposite(cmps[foundFieldIdx].getGenericType(), subList, indent + 1, null, null);
            }
        }

        boolean hasAllValues = true;
        for (int i = 0; i < cmps.length; i++) {
            if (values[i] == null) {
                hasAllValues = false;
                break;
            }
        }

        if (!hasAllValues) {
            throw new IllegalArgumentException("Couldn't find all the fields in %s: missing %s :(".formatted(clazz.getName(), IntStream.range(0, cmps.length).filter(i -> values[i] == null).mapToObj(i -> cmpNames[i]).collect(Collectors.joining(", "))));
        }

        Constructor<T> constructor;
        try {
            constructor = clazz.getDeclaredConstructor(Arrays.stream(cmps).map(RecordComponent::getType).toArray(Class[]::new));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("This record (%s) doesn't have a constructor???".formatted(clazz.getName()), e);
        }
        return constructor.newInstance(values);
    }

    private <T> T parseComposite(Type type, List<String> lines, int indent, String name, String nameValue) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        if (type instanceof Class<?> clazz) {
            if (Record.class.isAssignableFrom(clazz)) {
                return (T) this.parseRecord(lines, clazz, indent, name, nameValue);
            } else if (clazz.isInterface() && clazz.isSealed()) {
                var possibilities = clazz.getPermittedSubclasses();
                List<Exception> errors = new ArrayList<>();
                for (var possibility : possibilities) {
                    try {
                        return this.parseComposite(possibility, lines, indent, name, nameValue);
                    } catch (Exception e) {
                        errors.add(e);
                    }
                }
                throw new IllegalArgumentException("Couldn't parse %s: Errors: %s".formatted(type.getTypeName(), errors.stream().map(Throwable::getLocalizedMessage).collect(Collectors.joining("\n"))));
            }
        } else if (type instanceof ParameterizedType paramed
                && paramed.getRawType() instanceof Class<?> clazz
                && Collection.class.isAssignableFrom(clazz)
                && paramed.getActualTypeArguments()[0] instanceof Class<?> elementClazz) {
            return (T) this.parseCollection(lines, clazz, elementClazz, indent);
        }
        throw new UnsupportedOperationException("I don't know how to parse a %s".formatted(type.getTypeName()));
    }

    private <T> T parseCollection(List<String> lines, Class<T> clazz, Class<?> elementType, int indent) throws InvocationTargetException, InstantiationException, IllegalAccessException {
        boolean hasFoundFirstActualLine = false;
        List<Object> values = new ArrayList<>();
        lineLoop: for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
            var line = lines.get(lineIdx);
            if (line.isBlank()) {
                continue;
            }
            int foundIndent = 0;
            int bulletStartedAt = -1;
            int bulletEndedAt = -1;
            String valueName = null;
            for (int charIdx = 0; charIdx < line.length(); charIdx++) {
                char charAt = line.charAt(charIdx);
                // scenario 1: still going through indentation
                if (bulletStartedAt < 0 && (charAt == '\t' || charAt == ' ')) {
                    foundIndent += (charAt == '\t' ? 4 : 1);
                    continue;
                }
                // scenario 2: found the end of indentation
                if (bulletStartedAt < 0) {
                    if (foundIndent > indent && hasFoundFirstActualLine) {
                        continue lineLoop;
                    }

                    if (foundIndent < indent) {
                        break lineLoop;
                    }

                    // make sure there's actually a bullet there
                    if (charAt != '[') {
                        throw new IllegalArgumentException("This line should've started with a [] bullet: %s".formatted(line));
                    }

                    // if the first line with content is more indented than expected, then we just change the expected indent to match
                    if (!hasFoundFirstActualLine) {
                        indent = foundIndent;
                        hasFoundFirstActualLine = true;
                    }
                    bulletStartedAt = charIdx;
                    continue;
                }
                // scenario 3: going through bullet
                if (bulletEndedAt < 0) {
                    if (charAt != ']') {
                        continue;
                    }
                    bulletEndedAt = charIdx;
                    if (bulletEndedAt - bulletStartedAt > 1) {
                        valueName = line.substring(bulletStartedAt + 1, bulletEndedAt);
                    }
                }
                // scenario 4: gone through bullet, need to parse an atomic
                if (this.isAtomic(elementType)) {
                    values.add(this.parseAtomic(elementType, line.substring(bulletEndedAt + 1)));
                    continue lineLoop;
                }
                // scenario 5: gone through bullet, need to parse a composite
                values.add(this.parseComposite(elementType, lines.subList(lineIdx + 1, lines.size()), indent + 1, this.getNameComponent(elementType), valueName));
                continue lineLoop;
            }
        }
        if (List.class.isAssignableFrom(clazz)) {
            return (T) List.copyOf(values);
        } else if (Set.class.isAssignableFrom(clazz)) {
            return (T) Set.copyOf(values);
        } else {
            throw new UnsupportedOperationException("I don't know how to construct a %s".formatted(clazz));
        }
    }

    private <T> String getNameComponent(Class<T> elementType) {
        if (elementType.isRecord()) {
            for (var cmp : elementType.getRecordComponents()) {
                if (cmp.getName().equals("name")) {
                    return cmp.getName();
                }
            }
        }

        return null;
    }

    private Object parseAtomic(Type genericType, String substring) {
        if (genericType == String.class) {
            return substring.trim();
        } else if (genericType == Integer.class || genericType == int.class) {
            return Integer.parseInt(substring.trim());
        } else if (genericType == Long.class || genericType == long.class) {
            return Long.parseLong(substring.trim());
        } else if (genericType == Float.class || genericType == float.class) {
            return Float.parseFloat(substring.trim());
        } else if (genericType == Double.class || genericType == double.class) {
            return Double.parseDouble(substring.trim());
        } else if (genericType == Boolean.class || genericType == boolean.class) {
            return Boolean.parseBoolean(substring.trim());
        } else if (genericType == PostFilter.Fisp.class) {
            return PostFilter.Fisp.parse(substring.trim());
        }
        throw new UnsupportedOperationException("I don't know how to parse a %s :(".formatted(genericType));
    }

    private static String getName(RecordComponent component) {
        return component.getName();
    }
}
