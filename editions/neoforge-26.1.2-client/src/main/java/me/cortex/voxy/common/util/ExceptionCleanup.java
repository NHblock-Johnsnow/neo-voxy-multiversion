package me.cortex.voxy.common.util;

/** 释放各项资源并保留首因；单项失败不会跳过后续清理。 */
public final class ExceptionCleanup {
    private Throwable failure;

    public ExceptionCleanup(Throwable failure) {
        this.failure = failure;
    }

    public void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (this.failure == null) {
                this.failure = failure;
            } else if (this.failure != failure) {
                this.failure.addSuppressed(failure);
            }
        }
    }

    public void rethrow() {
        if (this.failure instanceof RuntimeException failure) throw failure;
        if (this.failure instanceof Error failure) throw failure;
        if (this.failure != null) throw new RuntimeException(this.failure);
    }
}
