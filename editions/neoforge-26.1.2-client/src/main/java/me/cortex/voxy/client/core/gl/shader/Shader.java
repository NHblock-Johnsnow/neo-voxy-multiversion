package me.cortex.voxy.client.core.gl.shader;

import me.cortex.voxy.common.util.ExceptionCleanup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlDebug;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public class Shader extends TrackedObject {
   private final int id;

   Shader(int program) {
      this.id = program;
   }

   public int id() {
      return this.id;
   }

   public void bind() {
      GL20.glUseProgram(this.id);
   }

   @Override
   public void free() {
      super.free0();
      GL20.glDeleteProgram(this.id);
   }

   public Shader name(String name) {
      return GlDebug.name(name, this);
   }

   public static Shader.Builder<Shader> make(IShaderProcessor... processor) {
      return makeInternal((a, b) -> new Shader(b), processor);
   }

   public static Shader.Builder<AutoBindingShader> makeAuto(IShaderProcessor... processor) {
      return makeInternal(AutoBindingShader::new, processor);
   }

   static <T extends Shader> Shader.Builder<T> makeInternal(Shader.Builder.IShaderObjectConstructor<T> constructor, IShaderProcessor[] processors) {
      List<IShaderProcessor> aa = new ArrayList<>(List.of(processors));
      Collections.reverse(aa);
      IShaderProcessor applicator = (type, source) -> source;

      for (IShaderProcessor processor : processors) {
         IShaderProcessor finalApplicator = applicator;
         applicator = (type, source) -> finalApplicator.process(type, processor.process(type, source));
      }

      return new Shader.Builder<>(constructor, applicator);
   }

   public static class Builder<T extends Shader> {
      final Map<String, String> defines = new HashMap<>();
      final Map<String, String> replacements = new LinkedHashMap<>();
      private final Map<ShaderType, String> sources = new HashMap<>();
      private final IShaderProcessor processor;
      private final Shader.Builder.IShaderObjectConstructor<T> constructor;

      private Builder(Shader.Builder.IShaderObjectConstructor<T> constructor, IShaderProcessor processor) {
         this.constructor = constructor;
         this.processor = processor;
      }

      public Shader.Builder<T> clone() {
         Shader.Builder<T> clone = new Shader.Builder<>(this.constructor, this.processor);
         clone.defines.putAll(this.defines);
         clone.sources.putAll(this.sources);
         clone.replacements.putAll(this.replacements);
         return clone;
      }

      public Shader.Builder<T> define(String name) {
         this.defines.put(name, "");
         return this;
      }

      public Shader.Builder<T> defineIf(String name, boolean condition) {
         if (condition) {
            this.defines.put(name, "");
         }

         return this;
      }

      public Shader.Builder<T> defineIf(String name, boolean condition, int value) {
         if (condition) {
            this.defines.put(name, Integer.toString(value));
         }

         return this;
      }

      public Shader.Builder<T> define(String name, int value) {
         this.defines.put(name, Integer.toString(value));
         return this;
      }

      public Shader.Builder<T> define(String name, float value) {
         this.defines.put(name, Float.toString(value) + "f");
         return this;
      }

      public Shader.Builder<T> define(String name, String value) {
         this.defines.put(name, value);
         return this;
      }

      public Shader.Builder<T> replace(String value, String replacement) {
         this.replacements.put(value, replacement);
         return this;
      }

      public Shader.Builder<T> add(ShaderType type, String id) {
         this.addSource(type, ShaderLoader.parse(id));
         return this;
      }

      public Shader.Builder<T> addSource(ShaderType type, String source) {
         this.sources.put(type, this.processor.process(type, source));
         return this;
      }

      public Shader.Builder<T> apply(Consumer<Shader.Builder<T>> applyer) {
         applyer.accept(this);
         return this;
      }

      private int compileToProgram() {
            int[] shaders = new int[this.sources.size()];
            int program = GL20C.glCreateProgram();
            try {
                String defs = this.defines.entrySet().stream()
                        .map(a -> "#define " + a.getKey() + " " + a.getValue() + "\n").collect(Collectors.joining());
                int i = 0;
                for (var entry : this.sources.entrySet()) {
                    String src = entry.getValue();
                    int firstLine = src.indexOf('\n') + 1;
                    src = src.substring(0, firstLine) + defs + src.substring(firstLine);
                    for (var replacement : this.replacements.entrySet()) {
                        src = src.replace(replacement.getKey(), replacement.getValue());
                    }
                    shaders[i++] = createShader(entry.getKey(), src);
                }
                for (int shader : shaders) GL20C.glAttachShader(program, shader);
                GL20C.glLinkProgram(program);
                printProgramLinkLog(program);
                verifyProgramLinked(program);
                for (int j = 0; j < shaders.length; j++) {
                    GL20C.glDetachShader(program, shaders[j]);
                    GL20C.glDeleteShader(shaders[j]);
                    shaders[j] = 0;
                }
                return program;
            } catch (RuntimeException | Error failure) {
                // program 销毁后再删除 stage，覆盖编译中途及链接失败两条路径。
                var cleanup = new ExceptionCleanup(failure);
                cleanup.run(() -> GL20C.glDeleteProgram(program));
                for (int shader : shaders) {
                    if (shader != 0) cleanup.run(() -> GL20C.glDeleteShader(shader));
                }
                throw failure;
            }
        }

      public T compile() {
            this.defineIf("IS_INTEL", Capabilities.INSTANCE.isIntel);
            this.defineIf("IS_WINDOWS", ThreadUtils.isWindows);
            int program = this.compileToProgram();
            try {
                return this.constructor.make(this, program);
            } catch (RuntimeException | Error failure) {
                new ExceptionCleanup(failure).run(() -> GL20C.glDeleteProgram(program));
                throw failure;
            }
        }

      private static void printProgramLinkLog(int program) {
         String log = GL20C.glGetProgramInfoLog(program);
         if (!log.isEmpty()) {
            Logger.error(log);
         }
      }

      private static void verifyProgramLinked(int program) {
         int result = GL20C.glGetProgrami(program, 35714);
         if (result != 1) {
            throw new RuntimeException("Shader program linking failed, see log for details");
         }
      }

      private static int createShader(ShaderType type, String src) {
            int shader = GL20C.glCreateShader(type.gl);
            try {
                long ptr = MemoryUtil.memAddress(MemoryUtil.memUTF8(src, true));
                try (var stack = MemoryStack.stackPush()) {
                    GL20C.nglShaderSource(shader, 1, stack.pointers(ptr).address0(), 0);
                } finally {
                    MemoryUtil.nmemFree(ptr);
                }
                GL20C.glCompileShader(shader);
                String log = GL20C.glGetShaderInfoLog(shader);
                if (!log.isEmpty()) Logger.warn(log);
                if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != GL20C.GL_TRUE) {
                    var failure = new RuntimeException("Shader compilation failed of type " + type.name()
                            + ", see log for details, dumped shader");
                    try {
                        Files.writeString(Path.of("SHADER_DUMP.txt"), src);
                    } catch (IOException dumpFailure) {
                        failure.addSuppressed(dumpFailure);
                    }
                    throw failure;
                }
                return shader;
            } catch (RuntimeException | Error failure) {
                new ExceptionCleanup(failure).run(() -> GL20C.glDeleteShader(shader));
                throw failure;
            }
        }

      protected interface IShaderObjectConstructor<J extends Shader> {
         J make(Shader.Builder<J> var1, int var2);
      }
   }
}
