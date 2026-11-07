package me.cortex.voxy.client.core.gl.shader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

public class PrintfInjector implements IShaderProcessor {
   private final GlBuffer textBuffer;
   private final HashMap<String, Integer> printfStringMap = new HashMap<>();
   private final HashMap<Integer, String> idToPrintfStringMap = new HashMap<>();
   private final int bindingIndex;
   private final Consumer<String> callback;
   private final Runnable preRun;

   public PrintfInjector(int bufferSize, int bufferBindingIndex, Consumer<String> callback) {
      this(bufferSize, bufferBindingIndex, callback, null);
   }

   public PrintfInjector(int bufferSize, int bufferBindingIndex, Consumer<String> callback, Runnable pre) {
      this.textBuffer = new GlBuffer(bufferSize * 4L + 4L);
      ARBDirectStateAccess.nglClearNamedBufferData(this.textBuffer.id, 33334, 36244, 5125, 0L);
      this.bindingIndex = bufferBindingIndex;
      this.callback = callback;
      this.preRun = pre;
   }

   private static int findNextCall(String src, int after) {
      while (true) {
         int idx = src.indexOf("printf", after);
         if (idx == -1) {
            return -1;
         }

         boolean lineComment = false;
         boolean multiLineComment = false;

         for (int i = 0; i < idx; i++) {
            if (src.charAt(i) == '/' && src.charAt(i + 1) == '/') {
               lineComment = true;
            }

            if (src.charAt(i) == '\n') {
               lineComment = false;
            }

            if (!lineComment && src.charAt(i) == '/' && src.charAt(i + 1) == '*') {
               multiLineComment = true;
            }

            if (!lineComment && src.charAt(i) == '*' && src.charAt(i + 1) == '/') {
               multiLineComment = false;
            }
         }

         if (!lineComment && !multiLineComment) {
            return idx;
         }

         after = idx + 1;
      }
   }

   private static void parsePrintfTypes(String fmtStr, List<Character> types) {
      for (int i = 0; i < fmtStr.length() - 1; i++) {
         if (fmtStr.charAt(i) == '%' && (i == 0 || fmtStr.charAt(i - 1) != '%')) {
            types.add(fmtStr.charAt(i + 1));
         }
      }
   }

   public String transformInject(String src) {
      String original = src;
      if (!src.contains("printf")) {
         return src;
      } else {
         int pos = 0;
         StringBuilder result = new StringBuilder();
         List<String> argVals = new ArrayList<>();
         List<Character> types = new ArrayList<>();
         int bufferInjection = Math.max(src.lastIndexOf("#version"), src.lastIndexOf("#extension"));
         bufferInjection = src.indexOf("\n", bufferInjection);
         result.append(src, 0, bufferInjection + 1);
         result.append(
            String.format(
               "layout(binding = %s, std430) restrict buffer PrintfOutputStream {\n    uint index;\n    uint stream[];\n} printfOutputStruct;\n",
               this.bindingIndex
            )
         );
         src = src.substring(bufferInjection + 1);
         boolean usedPrintf = false;

         while (true) {
            int nextCall = findNextCall(src, pos);
            if (nextCall == -1) {
               result.append(src, pos, src.length());
               if (!usedPrintf) {
                  return original;
               }

               return result.toString();
            }

            result.append(src, pos, nextCall);
            String sub = src.substring(nextCall);
            sub = sub.substring(sub.indexOf(34) + 1);
            sub = sub.substring(0, sub.indexOf(59));
            String fmtStr = sub.substring(0, sub.indexOf(34));
            String args = sub.substring(sub.indexOf(34));
            int prev = 0;
            int brace = 0;
            argVals.clear();

            for (int i = 0; i < args.length(); i++) {
               if (args.charAt(i) == '(' || args.charAt(i) == '[') {
                  brace++;
               }

               if (args.charAt(i) == ')' || args.charAt(i) == ']') {
                  brace--;
               }

               if (args.charAt(i) == ',' && brace == 0 || brace == -1) {
                  if (prev == 0) {
                     prev = i;
                  } else {
                     String arg = args.substring(prev + 1, i);
                     prev = i;
                     argVals.add(arg);
                     if (brace == -1) {
                        break;
                     }
                  }
               }
            }

            types.clear();
            parsePrintfTypes(fmtStr, types);
            if (types.size() != argVals.size()) {
               throw new IllegalStateException("Printf obj count dont match arg size");
            }

            StringBuilder subCode = new StringBuilder();
            subCode.append(
               String.format(
                  "{uint printfWriteIndex = atomicAdd(printfOutputStruct.index,%s);printfOutputStruct.stream[printfWriteIndex]=%s;",
                  types.size() + 1,
                  this.printfStringMap.computeIfAbsent(fmtStr, a -> {
                     int id = this.printfStringMap.size();
                     this.idToPrintfStringMap.put(id, a);
                     return id;
                  })
               )
            );

            for (int i = 0; i < types.size(); i++) {
               subCode.append("printfOutputStruct.stream[printfWriteIndex+").append(i + 1).append("]=");
               if (types.get(i) == 'd') {
                  subCode.append("uint(").append(argVals.get(i)).append(")");
               } else {
                  if (types.get(i) != 'f') {
                     throw new IllegalStateException("Unknown type " + types.get(i));
                  }

                  subCode.append("floatBitsToUint(").append(argVals.get(i)).append(")");
               }

               subCode.append(";");
            }

            subCode.append("}");
            result.append((CharSequence)subCode);
            usedPrintf = true;
            pos = src.indexOf(59, nextCall) + 1;
         }
      }
   }

   public void bind() {
      GL30.glBindBufferBase(37074, this.bindingIndex, this.textBuffer.id);
   }

   private void processResult(long ptr, long size) {
      int total = MemoryUtil.memGetInt(ptr);
      ptr += 4L;
      if (total != 0) {
         if (this.preRun != null) {
            this.preRun.run();
         }

         int cnt = 0;
         List<Character> types = new ArrayList<>();

         while (cnt < total) {
            int id = MemoryUtil.memGetInt(ptr);
            ptr += 4L;
            cnt++;
            String fmt = this.idToPrintfStringMap.get(id);
            if (fmt == null) {
               throw new IllegalStateException("Unknown id: " + id);
            }

            types.clear();
            parsePrintfTypes(fmt, types);
            Object[] args = new Object[types.size()];

            for (int i = 0; i < types.size(); i++) {
               if (types.get(i) == 'd') {
                  args[i] = MemoryUtil.memGetInt(ptr);
                  ptr += 4L;
                  cnt++;
               }

               if (types.get(i) == 'f') {
                  args[i] = Float.intBitsToFloat(MemoryUtil.memGetInt(ptr));
                  ptr += 4L;
                  cnt++;
               }
            }

            this.callback.accept(String.format(fmt, args));
         }
      }
   }

   public void download() {
      DownloadStream.INSTANCE.download(this.textBuffer, this::processResult);
      GL45.nglClearNamedBufferSubData(this.textBuffer.id, 33334, 0L, 4L, 36244, 5125, 0L);
   }

   public void free() {
      this.textBuffer.free();
   }

   @Override
   public String process(ShaderType type, String source) {
      return this.transformInject(source);
   }
}
