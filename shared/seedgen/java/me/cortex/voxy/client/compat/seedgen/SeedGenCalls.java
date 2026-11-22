package me.cortex.voxy.client.compat.seedgen;

import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** 仅在原版 SeedGen 0.3.1 的目标类加载时修改调用。 */
public final class SeedGenCalls {
    private static final String SEED = "mobius/voxyseedgen/";
    private static final String VOXY = "me/cortex/voxy/";
    private static final String SECTION = VOXY + "common/world/WorldSection";
    private static final String LADDER = SEED + "voxy/LadderWriter";
    private static final String TILES = SEED + "voxy/WrittenTiles";
    private static final String KEEP_UP = "(L" + SEED + "terrain/SeedTerrain;L" + VOXY + "common/world/WorldEngine;)V";

    private SeedGenCalls() {}

    public static void apply(ClassNode target, boolean modernSections) {
        String name = target.name;
        for (var method : target.methods) {
            switch (name) {
                case SEED + "voxy/SeedFill" -> {
                    switch (method.name) {
                        case "keepUp" -> {
                            if (!KEEP_UP.equals(method.desc)) break;
                            replaceBody(method, new VarInsnNode(ALOAD, 0), new VarInsnNode(ALOAD, 1), new VarInsnNode(ALOAD, 2),
                                    new MethodInsnNode(INVOKEVIRTUAL, name, "keepUpInner", method.desc, false), new InsnNode(RETURN));
                        }
                        case "finestLevelFor" -> returnZero(method);
                        case "work" -> useChunkWriter(method);
                    }
                }
                case SEED + "config/VoxySeedGenConfig" -> {
                    if (method.name.equals("reach")) returnZero(method);
                }
                case SEED + "voxy/RenderReach", SEED + "voxy/CoarseRings" -> {
                    if (method.desc.equals("(I)V") && (method.name.equals("apply") || method.name.equals("follow"))) {
                        replaceBody(method, new InsnNode(RETURN));
                    }
                }
                case LADDER -> {
                    if (method.name.equals("writeStack")) {
                        // 父区段只能从底层地形汇总，禁止绕过覆盖保护。
                        replaceBody(method, new TypeInsnNode(NEW, "java/lang/UnsupportedOperationException"), new InsnNode(DUP),
                                new LdcInsnNode("Direct SeedGen coarse writes are unavailable in Neo Voxy"),
                                new MethodInsnNode(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false),
                                new InsnNode(ATHROW));
                    }
                }
                case SEED + "voxy/VoxyChunkWriter" -> {
                    if (method.name.equals("chunkFinished")) guardChunkWrite(method);
                }
                case SEED + "voxy/ExistingChunks" -> {
                    if (modernSections && method.name.equals("findInSection")) scanColumns(method);
                }
            }
            if (modernSections) fixDescriptors(method);
        }
    }

    private static void replaceBody(MethodNode method, AbstractInsnNode... body) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        for (var insn : body) method.instructions.add(insn);
    }

    private static void returnZero(MethodNode method) {
        replaceBody(method, new InsnNode(ICONST_0), new InsnNode(IRETURN));
    }

    private static void useChunkWriter(MethodNode method) {
        var code = method.instructions;
        var allocation = find(code.getFirst(), insn -> insn instanceof TypeInsnNode type && type.getOpcode() == NEW && LADDER.equals(type.desc));
        var constructor = find(allocation, insn -> call(insn, LADDER, "<init>"));
        var detail = find(constructor, insn -> field(insn, "detailByDistance"));
        var demand = find(detail, insn -> field(insn, "demanded"));
        var afterDemand = (JumpInsnNode) find(demand, insn -> insn.getOpcode() == IFNULL);
        var holds = find(afterDemand.label, insn -> call(insn, TILES, "holds"));
        var level = find(holds, insn -> call(insn, TILES, "levelOf"));
        var result = (JumpInsnNode) find(level, insn -> insn.getOpcode() == GOTO);
        if (previous(result).getOpcode() != ISUB) throw unexpected(method.name);

        // 保留局部变量与栈帧布局，跳过粗层分配和查询。
        code.insertBefore(allocation, new InsnNode(ACONST_NULL));
        removeCode(code, allocation, constructor.getNext());
        int removed = 0;
        for (var insn = code.getFirst(); insn != null;) {
            var next = insn.getNext();
            if (call(insn, LADDER, "endPass")) {
                var receiver = previous(insn);
                if (!(receiver instanceof VarInsnNode var) || var.getOpcode() != ALOAD || var.var != 8) throw unexpected(method.name);
                code.remove(receiver);
                code.remove(insn);
                removed++;
            }
            insn = next;
        }
        if (removed != 4) throw unexpected(method.name);
        var first = previous(detail);
        code.insertBefore(first, new InsnNode(ICONST_0));
        code.insertBefore(first, new VarInsnNode(ISTORE, 18));
        code.insertBefore(first, new InsnNode(ACONST_NULL));
        code.insertBefore(first, new VarInsnNode(ASTORE, 19));
        removeCode(code, first, afterDemand.label);

        // levelOf 已能处理未记录的地块，只查询一次。
        code.insertBefore(holds, new IntInsnNode(BIPUSH, 5));
        code.insertBefore(holds, new MethodInsnNode(INVOKEVIRTUAL, TILES, "levelOf", "(III)I", false));
        code.insertBefore(holds, new InsnNode(ICONST_1));
        code.insertBefore(holds, new InsnNode(ISUB));
        removeCode(code, holds, result.label);
    }

    private static void guardChunkWrite(MethodNode method) {
        var code = method.instructions;
        var fill = find(code.getFirst(), insn -> insn instanceof MethodInsnNode call && call.owner.equals("java/util/Arrays") && call.desc.equals("([JIIJ)V"));
        for (var insn = fill.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals("java/util/Arrays") && call.desc.equals("([JIIJ)V")) fill = insn;
        }
        var uniform = (JumpInsnNode) findBack(fill, insn -> insn.getOpcode() == IFEQ);
        var mip = find(fill, insn -> insn instanceof MethodInsnNode call && call.name.equals("mipSection"));
        var engine = find(mip, insn -> field(insn, "engine"));
        var write = (MethodInsnNode) find(engine, insn -> call(insn, VOXY + "common/world/WorldUpdater", "insertUpdate"));
        var written = find(write, insn -> field(insn, "sectionsWritten"));
        var nextSection = find(written, insn -> insn instanceof IincInsnNode increment && increment.var == 12);
        var skip = (JumpInsnNode) findBack(uniform, insn -> insn instanceof JumpInsnNode jump && jump.getOpcode() == GOTO
                && code.indexOf(jump.label) > code.indexOf(written) && code.indexOf(jump.label) < code.indexOf(nextSection));
        if (!(previous(uniform) instanceof VarInsnNode flag) || flag.var != 17 ||
                !(previous(fill) instanceof VarInsnNode value) || value.var != 18) throw unexpected(method.name);

        // 先检查真实覆盖，跳过已有区段的 mip 和写入计数。
        removeCode(code, flag, previous(engine));
        code.insertBefore(write, new VarInsnNode(ILOAD, flag.var));
        code.insertBefore(write, new VarInsnNode(LLOAD, value.var));
        write.name = "insertUpdateIfMissing";
        write.desc = write.desc.replace(")V", "ZJ)Z");
        code.insert(write, new JumpInsnNode(IFEQ, skip.label));
    }

    private static void scanColumns(MethodNode method) {
        // 直接读取压缩区段，避免展开均匀体素。
        var code = method.instructions;
        code.remove(find(code.getFirst(), insn -> call(insn, SECTION, "_unsafeGetRawDataArray")));
        var scan = (MethodInsnNode) find(code.getFirst(), insn -> call(insn, SEED + "voxy/ExistingChunks", "hasBlock"));
        scan.setOpcode(INVOKEVIRTUAL);
        scan.owner = SECTION;
        scan.name = "hasNonAirInputColumn";
        scan.desc = "(II)Z";
        for (var insn : code) {
            if (insn instanceof FrameNode frame) {
                if (frame.local != null) frame.local.replaceAll(type -> "[J".equals(type) ? SECTION : type);
                if (frame.stack != null) frame.stack.replaceAll(type -> "[J".equals(type) ? SECTION : type);
            }
        }
        method.localVariables = null;
    }

    private static void fixDescriptors(MethodNode method) {
        var code = method.instructions;
        for (var insn = code.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode field && field.owner.equals(VOXY + "client/config/VoxyConfig")
                    && field.name.equals("sectionRenderDistance") && field.desc.equals("I")) {
                if (field.getOpcode() != GETFIELD) throw unexpected(method.name);
                field.desc = "F";
                if (field.getNext().getOpcode() == I2F) code.remove(field.getNext());
                else code.insert(field, new InsnNode(F2I));
            } else if (insn instanceof MethodInsnNode call) {
                if (call.owner.equals(VOXY + "common/Logger") && call.name.equals("info") && call.desc.equals("([Ljava/lang/Object;)V")) {
                    call.desc = "([Ljava/lang/Object;)Ljava/lang/String;";
                    code.insert(call, new InsnNode(POP));
                } else if (call.owner.equals(VOXY + "common/world/WorldEngine") && call.name.equals("saveSection") && call.desc.endsWith(")V")) {
                    call.desc = call.desc.substring(0, call.desc.length() - 1) + "Z";
                    code.insert(call, new InsnNode(POP));
                }
            }
        }
    }

    private static boolean call(AbstractInsnNode insn, String owner, String name) {
        return insn instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name);
    }

    private static boolean field(AbstractInsnNode insn, String name) {
        return insn instanceof FieldInsnNode field && field.name.equals(name);
    }

    private static AbstractInsnNode find(AbstractInsnNode insn, java.util.function.Predicate<AbstractInsnNode> match) {
        for (; insn != null; insn = insn.getNext()) if (match.test(insn)) return insn;
        throw unexpected("instruction");
    }

    private static AbstractInsnNode findBack(AbstractInsnNode insn, java.util.function.Predicate<AbstractInsnNode> match) {
        for (; insn != null; insn = insn.getPrevious()) if (match.test(insn)) return insn;
        throw unexpected("branch");
    }

    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        return findBack(insn.getPrevious(), node -> node.getOpcode() >= 0);
    }

    private static void removeCode(InsnList code, AbstractInsnNode first, AbstractInsnNode end) {
        for (var insn = first; insn != end;) {
            var next = insn.getNext();
            if (insn.getOpcode() >= 0 || insn instanceof FrameNode) code.remove(insn);
            insn = next;
        }
    }

    private static IllegalStateException unexpected(String location) {
        return new IllegalStateException("Unexpected Voxy SeedGen 0.3.1 bytecode at " + location);
    }
}
