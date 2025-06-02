package com.williambl.buskymore;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

// bini (buskymore ini)
public class Bini {
    public String unparse(Object value) {
        StringBuilder res = new StringBuilder(512);
        if (this.isAtomic(value)) {
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
            if (this.isAtomic(value)) {
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
            if (this.isAtomic(value)) {
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
            if (this.isAtomic(value)) {
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

    private boolean isAtomic(Object value) {
        return value instanceof String || value instanceof Number || value instanceof PostFilter.Fisp;
    }

    private <T> void parseRecord(List<String> lines, Class<T> clazz) {
        Map<RecordComponent, Object> values = new HashMap<>();
        Map<String, RecordComponent> cmpNames = Arrays.stream(clazz.getRecordComponents())
                .collect(Collectors.toMap(RecordComponent::getName, Function.identity()));
        for (var line : lines) {

        }
    }

    private static String getName(RecordComponent component) {
        return component.getName();
    }
}
