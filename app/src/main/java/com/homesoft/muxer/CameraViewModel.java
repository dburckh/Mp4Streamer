package com.homesoft.muxer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
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
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.UiThread;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.muxer.Mp4Muxer;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Executor;

@UnstableApi public class CameraViewModel extends AndroidViewModel {
    enum State {STOPPED, STREAMING, STOPPING};
    private static final int ONE_US = 1_000_000;
    private static final int FRAGMENT_DURATION_US = ONE_US;
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
    private final HandlerThread handlerThread = new HandlerThread("Camera");
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ConnectivityManager connectivityManager;

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

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
            if (linkProperties != null) {
                InetAddress inetAddress = null;
                for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                    inetAddress = linkAddress.getAddress();
                    if (inetAddress instanceof Inet4Address) {
                        break;
                    }
                }
                CameraViewModel.this.inetAddress = inetAddress;
            }
        }
    };

    private final MediaCodec.Callback callback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {

        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {

            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                try {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) > 0) {
                        mMp4Muxer.close();
                        mMp4Muxer = null;
                        fragmentServer.close();
                        fragmentServer = null;
                        mediaCodec.flush(); //I'm hoping this will cause the codec to restart at an I-Frame
                        jetty.setStopTimeout(1_000L);
                        jetty.stop();
                        jetty = null;
                        stateData.postValue(State.STOPPED);
                        return;
                    } else {
                        final ByteBuffer byteBuffer = codec.getOutputBuffer(index);
                        final ByteBuffer copy = ByteBuffer.allocateDirect(byteBuffer.remaining());
                        copy.put(byteBuffer);
                        copy.flip();
                        mMp4Muxer.writeSampleData(trackToken, copy, info);
                        //Log.d(TAG, "writeSampleData() ts=" + info.presentationTimeUs / 1000 + " flags=" + info.flags);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "onOutputBufferAvailable()", e);
                }
            }
            codec.releaseOutputBuffer(index, false);
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {

        }

        @OptIn(markerClass = UnstableApi.class) @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat mediaFormat) {
//            final ContentResolver contentResolver = getApplication().getContentResolver();
//            Uri outUri = getUri(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
//                    "Test", "video/mp4");
//            ParcelFileDescriptor outPfd = null;
//            try {
//                outPfd = contentResolver.openFileDescriptor(outUri, "w");
//                FileOutputStream out = new FileOutputStream(outPfd.getFileDescriptor());
//                FileChannel fileChannel = out.getChannel();
//                mMp4Muxer = new Mp4Muxer.Builder(fileChannel).setFragmentedMp4Enabled(true).build();
//                final Format format = Remuxer.getFormat(mediaFormat);
//                mMp4Muxer.setOrientation(sensorRotation);
//
//                trackToken = mMp4Muxer.addTrack(0, format);
//            } catch (FileNotFoundException e) {
//                throw new RuntimeException(e);
//            }

            // Hack to deal with in-exact key frame rates
            int fragmentDurationUs = FRAGMENT_DURATION_US >= ONE_US ? (FRAGMENT_DURATION_US - ONE_US / 4) : FRAGMENT_DURATION_US;
            mMp4Muxer = new Mp4Muxer.Builder(fragmentServer = new FragmentServer())
                    .setFragmentedMp4Enabled(true)
                    .setFragmentDurationUs(fragmentDurationUs)
                    .build();
            final Format format = Remuxer.getFormat(mediaFormat);
            mMp4Muxer.setOrientation(sensorRotation);

            trackToken = mMp4Muxer.addTrack(0, format);
        }
    };

    private final Size SIZE_1920 = new Size(1920, 1440);
    private final int PIXELS_1920 = getPixels(SIZE_1920);

    @Nullable
    private SurfaceHolder surfaceHolder;

    private Handler workHandler;

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

    private InetAddress inetAddress;

    @Nullable
    private MediaCodec mediaCodec;

    private MediaFormat mediaFormat;

    public final MutableLiveData<State> stateData = new MutableLiveData<>(State.STOPPED);

    @Nullable
    private Mp4Muxer mMp4Muxer;
    private Mp4Muxer.TrackToken trackToken;

    private FragmentServer fragmentServer;

    private Server jetty;

    public CameraViewModel(@NonNull Application application) {
        super(application);
        connectivityManager = (ConnectivityManager)application.getSystemService(Context.CONNECTIVITY_SERVICE);
        connectivityManager.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback);
        handlerThread.start();
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
                            mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, FRAGMENT_DURATION_US / ONE_US);
                            mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                            mediaCodec.setCallback(callback, getWorkHandler());
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

    public InetSocketAddress getInetSocketAddress() {
        return new InetSocketAddress(inetAddress, 8080);
    }

    @UiThread
    private Handler getWorkHandler() {
        if (workHandler == null) {
            workHandler = new Handler(handlerThread.getLooper());
        }
        return workHandler;
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
        updateCameraCaptureSession(stateData.getValue() == State.STREAMING ? this.codecSurface : null);
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

    private Uri getUri(Uri collection, String fileName, String mimeType) throws UnsupportedOperationException {
        ContentResolver resolver = getApplication().getContentResolver();

        ContentValues newMediaValues = new ContentValues();
        newMediaValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        newMediaValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);

        return resolver.insert(collection, newMediaValues);
    }
    @UiThread
    public void startStream() {
        if (mediaCodec != null) {
            updateCameraCaptureSession(codecSurface);
            if (inetAddress != null) {
                getWorkHandler().post(()->{
                    jetty = new Server(getInetSocketAddress());
                    jetty.setHandler(new ServletHandler());
                    try {
                        jetty.start();
                        stateData.postValue(State.STREAMING);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

            }
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    @UiThread
    public void stopStream() {
        if (mediaCodec != null) {
            updateCameraCaptureSession(null);
            if (jetty != null) {
                stateData.postValue(State.STOPPING);
                mediaCodec.signalEndOfInputStream();
            }
        }
    }



    @Override
    protected void onCleared() {
        super.onCleared();
        if (mediaCodec != null) {
            mediaCodec.release();
        }
        connectivityManager.unregisterNetworkCallback(networkCallback);
    }

    class ServletHandler extends AbstractHandler {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
            response.setHeader("content-type", "video/mp4");
            OutputStream out = response.getOutputStream();
            try {
                out.write(fragmentServer.getHeader().array());
                //out.flush();
                ByteBuffer byteBuffer = null;
                while (true) {
                    byteBuffer = fragmentServer.getFragment(byteBuffer);
                    Log.d(TAG, "Sending " + byteBuffer.remaining());
                    out.write(byteBuffer.array());
                    out.flush();
                }
            } catch (InterruptedException | ClosedChannelException e) {

            }
            Log.d(TAG, "Closed");
            out.close();
        }
    }
}
