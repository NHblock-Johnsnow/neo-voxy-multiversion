package me.cortex.voxy.commonImpl.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;

/** 复合标签按键排序，列表保留顺序，避免同内容生成重复变体。 */
final class NbtVariantKey {
    private NbtVariantKey() {}

    static String of(CompoundTag data) {
        var canonical = new StringBuilder(128);
        append(data, canonical);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void append(Tag tag, StringBuilder output) {
        output.append(tag.getId()).append(':');
        if (tag instanceof CompoundTag compound) {
            var keys = new ArrayList<>(compound.getAllKeys());
            keys.sort(String::compareTo);
            output.append('{');
            for (String key : keys) {
                output.append(key.length()).append(':').append(key).append('=');
                Tag value = compound.get(key);
                if (value != null) append(value, output);
                output.append(';');
            }
            output.append('}');
        } else if (tag instanceof ListTag list) {
            output.append('[');
            for (int index = 0; index < list.size(); index++) {
                append(list.get(index), output);
                output.append(';');
            }
            output.append(']');
        } else {
            String value = tag.toString();
            output.append(value.length()).append(':').append(value);
        }
    }
}
