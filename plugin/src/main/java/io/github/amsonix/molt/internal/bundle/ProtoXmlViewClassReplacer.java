package io.github.amsonix.molt.internal.bundle;

import com.android.aapt.Resources;
import io.github.amsonix.molt.internal.rename.ComponentRenameEntry;
import io.github.amsonix.molt.internal.rename.RenameMapping;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 替换 AAB 内 aapt2 proto 格式 layout（Resources.XmlNode）中的 View FQCN。 */
public final class ProtoXmlViewClassReplacer {

    public enum FormatStatus {
        SUPPORTED,
        UNSUPPORTED,
        PARSE_FAILED
    }

    public static final class RewriteResult {
        private final byte[] bytes;
        private final FormatStatus formatStatus;
        private final int replacementCount;
        private final String failureReason;

        public RewriteResult(
                byte[] bytes,
                FormatStatus formatStatus,
                int replacementCount,
                String failureReason
        ) {
            this.bytes = bytes;
            this.formatStatus = formatStatus;
            this.replacementCount = replacementCount;
            this.failureReason = failureReason;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public FormatStatus getFormatStatus() {
            return formatStatus;
        }

        public int getReplacementCount() {
            return replacementCount;
        }

        public String getFailureReason() {
            return failureReason;
        }
    }

    private ProtoXmlViewClassReplacer() {
    }

    public static byte[] replace(byte[] input, RenameMapping mapping) {
        return rewrite(input, mapping).getBytes();
    }

    public static RewriteResult rewrite(byte[] input, RenameMapping mapping) {
        if (input.length == 0 || isBinaryXml(input) || looksLikeTextXml(input)) {
            return new RewriteResult(input, FormatStatus.UNSUPPORTED, 0, "not proto XML");
        }
        try {
            Resources.XmlNode root = Resources.XmlNode.parseFrom(input);
            if (!root.hasElement() && !root.hasText()) {
                return new RewriteResult(input, FormatStatus.UNSUPPORTED, 0, "proto XML root has no content");
            }
            Map<String, String> forward = toForwardMap(mapping);
            List<ComponentRenameEntry> byLength = mapping.entries().stream()
                    .sorted(Comparator.comparingInt((ComponentRenameEntry e) -> e.getOriginal().length()).reversed())
                    .collect(Collectors.toList());
            int[] replacementCount = {0};
            Resources.XmlNode rewritten = rewriteNode(root, forward, byLength, replacementCount);
            if (rewritten.equals(root)) {
                return new RewriteResult(input, FormatStatus.SUPPORTED, 0, null);
            }
            return new RewriteResult(
                    rewritten.toByteArray(),
                    FormatStatus.SUPPORTED,
                    replacementCount[0],
                    null
            );
        } catch (Exception exception) {
            return new RewriteResult(
                    input,
                    FormatStatus.PARSE_FAILED,
                    0,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
        }
    }

    private static Map<String, String> toForwardMap(RenameMapping mapping) {
        Map<String, String> forward = new HashMap<>();
        for (ComponentRenameEntry entry : mapping.entries()) {
            forward.put(entry.getOriginal(), entry.getObfuscated());
        }
        return forward;
    }

    private static Resources.XmlNode rewriteNode(
            Resources.XmlNode node,
            Map<String, String> forward,
            List<ComponentRenameEntry> byLength,
            int[] replacementCount
    ) {
        Resources.XmlNode.Builder builder = node.toBuilder();
        if (node.hasElement()) {
            builder.setElement(rewriteElement(node.getElement(), forward, byLength, replacementCount));
        }
        if (node.hasText()) {
            builder.setText(remapString(node.getText(), forward, byLength, replacementCount));
        }
        return builder.build();
    }

    private static Resources.XmlElement rewriteElement(
            Resources.XmlElement element,
            Map<String, String> forward,
            List<ComponentRenameEntry> byLength,
            int[] replacementCount
    ) {
        Resources.XmlElement.Builder builder = element.toBuilder();
        builder.setName(remapString(element.getName(), forward, byLength, replacementCount));
        for (int i = 0; i < element.getAttributeCount(); i++) {
            Resources.XmlAttribute attribute = element.getAttribute(i);
            Resources.XmlAttribute.Builder attrBuilder = attribute.toBuilder();
            attrBuilder.setValue(remapString(attribute.getValue(), forward, byLength, replacementCount));
            builder.setAttribute(i, attrBuilder.build());
        }
        for (int i = 0; i < element.getChildCount(); i++) {
            builder.setChild(i, rewriteNode(element.getChild(i), forward, byLength, replacementCount));
        }
        return builder.build();
    }

    private static String remapString(
            String value,
            Map<String, String> forward,
            List<ComponentRenameEntry> byLength,
            int[] replacementCount
    ) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String exact = forward.get(value);
        if (exact != null) {
            replacementCount[0]++;
            return exact;
        }
        // 相对组件名 ".MainActivity" / ".foo.Bar" → 匹配 FQCN 后缀，输出完整混淆 FQCN（最安全）
        if (value.startsWith(".")) {
            String relative = value.substring(1);
            for (ComponentRenameEntry entry : byLength) {
                String original = entry.getOriginal();
                if (original.equals(relative) || original.endsWith("." + relative)) {
                    replacementCount[0]++;
                    return entry.getObfuscated();
                }
            }
        }
        for (ComponentRenameEntry entry : byLength) {
            if (value.equals(entry.getOriginal())) {
                return entry.getObfuscated();
            }
        }
        return value;
    }

    private static boolean isBinaryXml(byte[] input) {
        return input.length >= 2
                && (input[0] & 0xFF) == 0x03
                && (input[1] & 0xFF) == 0x00;
    }

    private static boolean looksLikeTextXml(byte[] input) {
        int limit = Math.min(input.length, 64);
        for (int i = 0; i < limit; i++) {
            int value = input[i] & 0xFF;
            if (!Character.isWhitespace((char) value)) {
                return value == '<';
            }
        }
        return false;
    }
}
