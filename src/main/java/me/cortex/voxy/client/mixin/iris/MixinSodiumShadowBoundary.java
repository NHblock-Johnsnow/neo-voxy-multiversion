package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.common.Logger;
import net.caffeinemc.mods.sodium.client.gl.shader.GlShader;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies Voxy's complementary ownership dither to Iris shadow casters. */
@Mixin(value = SodiumPrograms.class, remap = false)
public abstract class MixinSodiumShadowBoundary {
    private static final String MARKER = "voxy_shadow_boundary_distance";
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)(?=\\s*\\{)");
    private static boolean warned;

    @Inject(method = "createGlShaders", at = @At("HEAD"))
    private void voxy$fadeShadowCasters(String passName,
                                        Map<PatchShaderType, String> transformed,
                                        CallbackInfoReturnable<Map<PatchShaderType, GlShader>> cir) {
        if (!passName.equals("shadow") && !passName.equals("shadow_cutout")) {
            return;
        }

        // A varying would have to be forwarded through optional geometry or
        // tessellation stages. Leave those uncommon shader pipelines intact.
        if (transformed.get(PatchShaderType.GEOMETRY) != null
                || transformed.get(PatchShaderType.TESS_CONTROL) != null
                || transformed.get(PatchShaderType.TESS_EVAL) != null) {
            return;
        }

        String vertex = transformed.get(PatchShaderType.VERTEX);
        String fragment = transformed.get(PatchShaderType.FRAGMENT);
        if (vertex == null || fragment == null
                || vertex.contains(MARKER) || fragment.contains(MARKER)) {
            return;
        }

        String renamedVertex = renameMain(vertex);
        String renamedFragment = renameMain(fragment);
        if (renamedVertex == null || renamedFragment == null) {
            warnOnce("Could not add the Voxy boundary fade to an Iris shadow shader");
            return;
        }

        transformed.put(PatchShaderType.VERTEX, renamedVertex + VERTEX_WRAPPER);
        transformed.put(PatchShaderType.FRAGMENT, renamedFragment + FRAGMENT_WRAPPER);
    }

    private static String renameMain(String source) {
        Matcher matcher = MAIN.matcher(source);
        return matcher.find()
                ? matcher.replaceFirst("void voxy_shadow_original_main()")
                : null;
    }

    private static void warnOnce(String message) {
        if (!warned) {
            warned = true;
            Logger.warn(message);
        }
    }

    private static final String VERTEX_WRAPPER = """

            out float voxy_shadow_boundary_distance;

            void main() {
                voxy_shadow_original_main();
                vec3 voxy_relative_position = _vert_position + u_RegionOffset
                        + _get_draw_translation(_draw_id);
                voxy_shadow_boundary_distance = length(voxy_relative_position.xz);
            }
            """;

    private static final String FRAGMENT_WRAPPER = """

            in float voxy_shadow_boundary_distance;
            uniform float voxyLodBoundaryFadeStart;
            uniform float voxyLodBoundaryFadeEnd;

            const int voxy_bayer_8x8[64] = int[](
                 0, 48, 12, 60,  3, 51, 15, 63,
                32, 16, 44, 28, 35, 19, 47, 31,
                 8, 56,  4, 52, 11, 59,  7, 55,
                40, 24, 36, 20, 43, 27, 39, 23,
                 2, 50, 14, 62,  1, 49, 13, 61,
                34, 18, 46, 30, 33, 17, 45, 29,
                10, 58,  6, 54,  9, 57,  5, 53,
                42, 26, 38, 22, 41, 25, 37, 21
            );

            float voxy_shadow_dither(ivec2 pixel) {
                ivec2 p = pixel & ivec2(7);
                return (float(voxy_bayer_8x8[p.y * 8 + p.x]) + 0.5) * (1.0 / 64.0);
            }

            void main() {
                if (voxyLodBoundaryFadeEnd > voxyLodBoundaryFadeStart) {
                    float coverage = smoothstep(voxyLodBoundaryFadeStart,
                            voxyLodBoundaryFadeEnd, voxy_shadow_boundary_distance);
                    if (voxy_shadow_dither(ivec2(gl_FragCoord.xy)) < coverage) {
                        discard;
                    }
                }
                voxy_shadow_original_main();
            }
            """;
}
