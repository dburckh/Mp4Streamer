package com.homesoft.muxer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.AndroidViewModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;

public class CameraViewModel extends AndroidViewModel {
    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private static final String TAG = CameraViewModel.class.getSimpleName();

    private static int getPixels(Size size) {
        return size.getWidth() * size.getHeight();
    }
    //private final HandlerThread handlerThread = new HandlerThread("Camera");
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Executor executor = mainHandler::post;

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            if (surfaceHolder != null && codecSurface != null) {
                configureCamera(cameraDevice, surfaceHolder, codecSurface);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice = null;
            cameraCaptureSession = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "CameraDevice.onError() " + error);
        }
    };

    private final MediaCodec.Callback callback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {

        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            codec.releaseOutputBuffer(index, false);
            Log.d(TAG, "onOutputBufferAvailable()");
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {

        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {

        }
    };

    private final Size SIZE_1920 = new Size(1920, 1440);
    private final int PIXELS_1920 = getPixels(SIZE_1920);

    @Nullable
    private SurfaceHolder surfaceHolder;

    @Nullable
    private Surface codecSurface;

    @Nullable
    private CameraDevice cameraDevice;

    CameraCaptureSession.StateCallback configureCallback;
    @Nullable
    private CameraCaptureSession cameraCaptureSession;

    private Size imageSize;
    private int sensorRotation;
    private Range<Integer> selectedFps;

    @Nullable
    private MediaCodec mediaCodec;

    private MediaFormat mediaFormat;

    private boolean streaming;
    public CameraViewModel(@NonNull Application application) {
        super(application);
    }

    public boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Locates a suitable camera and sets up the MediaCodec
     */
    @UiThread
    @SuppressLint("MissingPermission")
    public boolean init() {
        if (imageSize == null && hasCameraPermission()) {
            final CameraManager cameraManager = (CameraManager) getApplication().getSystemService(Context.CAMERA_SERVICE);
            try {
                final String[] ids = cameraManager.getCameraIdList();
                for (String id : ids) {
                    CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(id);
                    int lensFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                    if (CameraCharacteristics.LENS_FACING_FRONT != lensFacing) {
                        continue;
                    }
                    StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    Size[] sizes = map.getOutputSizes(ImageFormat.PRIVATE);
                    for (Size size : sizes) {
                        if (size.equals(SIZE_1920)) {
                            imageSize = size;
                            break;
                        } else if (getPixels(size) <= PIXELS_1920
                                && (imageSize == null || getPixels(imageSize) <= getPixels(size))) {
                            imageSize = size;
                        }
                    }
                    if (imageSize != null) {
                        sensorRotation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                        Range<Integer>[] fps = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                        Arrays.sort(fps, (o1, o2) -> {
                            int rc = Integer.compare(o1.getUpper(), o2.getUpper());
                            if (rc == 0) {
                                return Integer.compare(o1.getLower(), o2.getLower());
                            }
                            return rc;
                        });
                        for (Range<Integer> range : fps) {
                            if (range.getLower() >= 24) {
                                selectedFps = range;
                                break;
                            }
                        }
                        if (selectedFps == null) {
                            selectedFps = fps[fps.length - 1];
                        }

                        try {
                            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                            mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, imageSize.getWidth(), imageSize.getHeight());
                            mediaFormat.setFloat(MediaFormat.KEY_FRAME_RATE, selectedFps.getLower());
                            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                            int pixelsPerSecond = selectedFps.getUpper() * imageSize.getWidth() * imageSize.getHeight();
                            //Try 1/8 or 12.5%
                            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, (pixelsPerSecond / 8));
                            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                            mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                            mediaCodec.setCallback(callback);
                            codecSurface = mediaCodec.createInputSurface();
                            mediaCodec.start();
                        } catch (IOException e) {
                            Log.e(TAG, "createEncoderByType()");
                        }
                        cameraManager.openCamera(id, cameraStateCallback, mainHandler);
                        break;
                    }
                }

            } catch (CameraAccessException e) {
                Log.e(TAG, "Debug", e);
            }
        }
        return mediaCodec != null;
    }

    @Nullable
    public Size getDisplaySize(Activity activity) {
        int deviceOrientation = activity.getWindowManager().getDefaultDisplay().getRotation();

        // Get device orientation in degrees
        // We use front camera, so negate
        int deviceRotation = -ORIENTATIONS.get(deviceOrientation);


        // Calculate desired JPEG orientation relative to camera orientation to make
        // the image upright relative to the device orientation
        int rotation = (sensorRotation + deviceRotation + 360) % 360;
        if (imageSize != null) {
            if (rotation == 90 || rotation == 270) {
                return new Size(imageSize.getHeight(), imageSize.getWidth());
            } else {
                return imageSize;
            }
        }
        return null;
    }

    private void configureCamera(@NonNull CameraDevice cameraDevice,
                                 @NonNull SurfaceHolder surfaceHolder,
                                 @NonNull Surface codecSurface) {
        try {
            //surfaceHolder.setFixedSize(imageSize.getWidth(), imageSize.getHeight());
            configureCallback = new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    cameraCaptureSession = session;
                    updateCameraCaptureSession();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    cameraCaptureSession = null;
                    // TODO: Handle
                }
            };
            ArrayList<Surface> surfaceList = new ArrayList<>(2);
            surfaceList.add(surfaceHolder.getSurface());
            surfaceList.add(codecSurface);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                final ArrayList<OutputConfiguration> outputList = new ArrayList<>(surfaceList.size());
                for (Surface surface : surfaceList) {
                    outputList.add(new OutputConfiguration(surface));
                }
                SessionConfiguration sessionConfiguration = new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputList,
                        executor,
                        configureCallback);
                cameraDevice.createCaptureSession(sessionConfiguration);
            } else {
                cameraDevice.createCaptureSession(
                        surfaceList,
                        configureCallback,
                        mainHandler);
            }

        } catch (CameraAccessException e) {
            Log.e(TAG, "createCaptureSession()", e);
        }
    }

    @UiThread
    public void setSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {
        if (this.surfaceHolder != surfaceHolder) {
            this.surfaceHolder = surfaceHolder;
            if (configureCallback == null) {
                if (cameraDevice != null && codecSurface != null && surfaceHolder != null) {
                    configureCamera(cameraDevice, surfaceHolder, codecSurface);
                }
            } else {
                updateCameraCaptureSession();
            }
        }


    }
    private void updateCameraCaptureSession() {
        updateCameraCaptureSession(isStreaming() ? this.codecSurface : null);
    }

    private void updateCameraCaptureSession(Surface codecSurface) {
        if (cameraCaptureSession != null) {
            Surface previewSurface = surfaceHolder == null ? null : surfaceHolder.getSurface();
            if (previewSurface == null && codecSurface == null) {
                cameraCaptureSession.close();
                cameraCaptureSession = null;
                configureCallback = null;
            }
            try {
                CaptureRequest.Builder builder = cameraCaptureSession.getDevice()
                        .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedFps);
                if (previewSurface != null) {
                    builder.addTarget(previewSurface);
                }
                if (codecSurface != null) {
                    builder.addTarget(codecSurface);
                }
                cameraCaptureSession.setRepeatingRequest(builder.build(), null, null);

            } catch (CameraAccessException e) {
                Log.e(TAG, "onConfigured()", e);
            }
        }
    }

    public boolean isStreaming() {
        return streaming;
    }


    @UiThread
    public void startStream() {
        if (mediaCodec != null) {
            updateCameraCaptureSession(codecSurface);
            streaming = true;
            // TODO: Start webserver
        }
    }

    @UiThread
    public void stopStream() {
        if (mediaCodec != null) {
            updateCameraCaptureSession(null);
            streaming = false;
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        if (mediaCodec != null) {
            mediaCodec.release();
        }
    }
}
