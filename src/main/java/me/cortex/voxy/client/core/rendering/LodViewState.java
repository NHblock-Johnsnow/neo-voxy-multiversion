package me.cortex.voxy.client.core.rendering;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** 屏幕细节判断的复用条件；缩放、分辨率或精度变化后必须重新选择。 */
public final class LodViewState {
    private final Matrix4f transform = new Matrix4f();
    private int width, height;
    private float subdivision;
    private double nearDetail;
    private boolean valid;

    public boolean matches(Matrix4fc transform, int width, int height, float subdivision,
                           double nearDetail, float tolerance) {
        return this.valid && this.width == width && this.height == height
                && this.subdivision == subdivision && this.nearDetail == nearDetail
                && this.transform.equals(transform, tolerance);
    }

    public void set(Matrix4fc transform, int width, int height, float subdivision, double nearDetail) {
        this.transform.set(transform);
        this.width = width;
        this.height = height;
        this.subdivision = subdivision;
        this.nearDetail = nearDetail;
        this.valid = true;
    }

    public void invalidate() {
        this.valid = false;
    }
}
