package dev.aisystems.quicksrplayerlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Verifies the GLES contract needed by an opaque RGB-only QuickSR upload path. */
@RunWith(AndroidJUnit4.class)
public final class RgbTextureUploadInstrumentedTest {
    @Test
    public void rgb8TextureReadbackProducesOpaquePixels() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        assertNotEquals(EGL14.EGL_NO_DISPLAY, display);
        int[] versions = new int[2];
        assertTrue(EGL14.eglInitialize(display, versions, 0, versions, 1));

        int[] configAttributes = {
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] configCount = new int[1];
        assertTrue(EGL14.eglChooseConfig(
                display,
                configAttributes,
                0,
                configs,
                0,
                configs.length,
                configCount,
                0));
        assertEquals(1, configCount[0]);

        int[] contextAttributes = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                EGL14.EGL_NONE
        };
        EGLContext context = EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                contextAttributes,
                0);
        assertNotEquals(EGL14.EGL_NO_CONTEXT, context);
        int[] surfaceAttributes = {
                EGL14.EGL_WIDTH, 4,
                EGL14.EGL_HEIGHT, 2,
                EGL14.EGL_NONE
        };
        EGLSurface surface = EGL14.eglCreatePbufferSurface(
                display,
                configs[0],
                surfaceAttributes,
                0);
        assertNotEquals(EGL14.EGL_NO_SURFACE, surface);
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));

        int[] texture = new int[1];
        int[] framebuffer = new int[1];
        try {
            GLES20.glGenTextures(1, texture, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES30.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES30.GL_RGB8,
                    4,
                    2,
                    0,
                    GLES20.GL_RGB,
                    GLES20.GL_UNSIGNED_BYTE,
                    null);
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());

            byte[] expectedRgb = {
                    1, 2, 3,
                    11, 12, 13,
                    21, 22, 23,
                    31, 32, 33,
                    41, 42, 43,
                    51, 52, 53,
                    61, 62, 63,
                    71, 72, 73
            };
            ByteBuffer rgb = ByteBuffer.allocateDirect(expectedRgb.length)
                    .order(ByteOrder.nativeOrder());
            rgb.put(expectedRgb).flip();
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
            GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    4,
                    2,
                    GLES20.GL_RGB,
                    GLES20.GL_UNSIGNED_BYTE,
                    rgb);
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());

            GLES20.glGenFramebuffers(1, framebuffer, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0]);
            GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    texture[0],
                    0);
            assertEquals(
                    GLES20.GL_FRAMEBUFFER_COMPLETE,
                    GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER));

            ByteBuffer actual = ByteBuffer.allocateDirect(4 * 2 * 4)
                    .order(ByteOrder.nativeOrder());
            GLES20.glReadPixels(
                    0,
                    0,
                    4,
                    2,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    actual);
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
            actual.position(0);
            for (int pixel = 0; pixel < 8; pixel++) {
                assertEquals(expectedRgb[pixel * 3] & 0xff, actual.get() & 0xff);
                assertEquals(expectedRgb[pixel * 3 + 1] & 0xff, actual.get() & 0xff);
                assertEquals(expectedRgb[pixel * 3 + 2] & 0xff, actual.get() & 0xff);
                assertEquals(255, actual.get() & 0xff);
            }
        } finally {
            if (framebuffer[0] != 0) {
                GLES20.glDeleteFramebuffers(1, framebuffer, 0);
            }
            if (texture[0] != 0) {
                GLES20.glDeleteTextures(1, texture, 0);
            }
            EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }
}
