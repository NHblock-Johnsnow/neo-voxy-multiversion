package me.cortex.voxy.client.core.rendering;

import org.joml.Matrix4f;

/** 检查原生区段选择、跨区段保守取级、缓存失效。 */
public final class NativeLodSelectionVerification {
    private static int checks;

    public static void run() {
        var selection = new NativeLodSelection();
        var projection = projection(70);
        selection.update(projection,projection,1920,1080,0,0,0,256,256);
        expect(selection.level(0,10000,0,16,10016,16)==0,"Native full detail uses horizontal distance");
        int far = selection.level(0,0,-2048,16,16,-2032);
        expect(far>0,"Far native section should be coarse");
        int evaluations = selection.evaluations;
        expect(selection.level(0,0,-2048,16,16,-2032)==far,"Cached section changed level");
        expect(selection.evaluations==evaluations && selection.cacheHits>0,"Section decisions were not reused");
        selection.update(projection,projection,1920,1080,0,0,0,256,256);
        expect(selection.evaluations==evaluations,"Stationary view discarded cache");
        var zoom = projection(1);
        selection.update(zoom,zoom,1920,1080,0,0,0,256,256);
        expect(selection.evaluations==0,"Zoom did not invalidate cache");
        expect(selection.level(0,0,-2048,16,16,-2032)==0,"Zoom failed to refine native sections");
        int previous = 0;
        for (float quality : new float[]{28,64,123,256,512,768,1024}) {
            selection.update(projection,projection,1920,1080,0,0,0,quality,256);
            int level = selection.level(-16,-16,-1024,16,16,-1008);
            expect(level>=previous,"Lower quality refined geometry");
            previous=level;
        }
        selection.update(projection,projection,1920,1080,0,0,0,256,256);
        previous=selection.level(0,0,-2048,16,16,-2032);
        selection.update(projection,projection,3840,2160,0,0,0,256,256);
        expect(selection.level(0,0,-2048,16,16,-2032)<=previous,"Higher resolution coarsened geometry");
        for (double camera : new double[]{0,-.125,30000000.25,-30000000.25}) {
            selection.update(projection,projection,1920,1080,camera,100,camera,256,256);
            double x0 = Math.floor(camera/32)*32, z0 = Math.floor((camera-4096)/32)*32;
            int level = selection.level(x0,96,z0,x0+64,128,z0+64);
            int expected = 4;
            for (int z=0;z<2;z++) for (int x=0;x<2;x++) {
                expected=Math.min(expected,selection.level(x0+x*32,96,z0+z*32,x0+(x+1)*32,128,z0+(z+1)*32));
            }
            expect(level==expected,"Cross-section mesh must use the finest touched level");
            var transform = new Matrix4f().translate(8,-4,-4090);
            int expectedLevel = selection.level(camera+8,96,camera-4090,camera+24,112,camera-4074);
            float grid = selection.gridSize(transform,camera,100,camera,0,0,0,16,16,16);
            expect(Math.abs(grid-(expectedLevel==0?0:1<<expectedLevel))<1e-4,"Translated model disagrees with world sections");
            transform.scale(2);
            int scaledLevel = selection.level(camera+8,96,camera-4090,camera+40,128,camera-4058);
            expect(Math.abs(selection.gridSize(transform,camera,100,camera,0,0,0,16,16,16)-(scaledLevel==0?0:(1<<scaledLevel)/2f))<1e-4,
                    "Scaled mesh must preserve native world voxel size");
        }
        selection.update(projection,projection,0,0,0,0,0,256,256);
        expect(selection.level(0,0,-1000,16,16,-984)==0,"Invalid viewport must keep full detail");
        selection.update(projection,projection,1920,1080,0,0,0,256,256);
        expect(selection.level(-1e20,-1e20,-1e20,1e20,1e20,1e20)==0,"Huge mesh must obey traversal budget");
        expect(selection.level(Double.NaN,0,0,1,1,1)==0,"Invalid mesh must keep full detail");
        System.out.println("Passed "+checks+" native section LOD checks");
    }

    private static Matrix4f projection(float fov) {
        return new Matrix4f().perspective((float)Math.toRadians(fov),16f/9,.1f,50000);
    }

    private static void expect(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
