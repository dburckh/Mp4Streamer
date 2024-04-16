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
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
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
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.muxer.Mp4Muxer;

import com.google.common.net.HttpHeaders;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

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
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@UnstableApi public class CameraViewModel extends AndroidViewModel {
    /**
     * Connection value indicating that MediaCodec is shutdown and needs to be restarted
     */
    public static final int MEDIA_CODEC_SHUTDOWN = -1;

    public static Format getFormat(MediaFormat mediaFormat) {
        final String mimeType = mediaFormat.getString(MediaFormat.KEY_MIME);
        final Format.Builder builder = new Format.Builder().setSampleMimeType(mimeType);
        if (mimeType.startsWith(MimeTypes.BASE_TYPE_VIDEO)) {
            builder.setWidth(mediaFormat.getInteger(MediaFormat.KEY_WIDTH))
                    .setHeight(mediaFormat.getInteger(MediaFormat.KEY_HEIGHT));
            if (mediaFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                builder.setRotationDegrees(mediaFormat.getInteger(MediaFormat.KEY_ROTATION));
            }
        } else if (mimeType.startsWith(MimeTypes.BASE_TYPE_AUDIO)) {
            builder.setChannelCount(mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    .setSampleRate(mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE));
        } else {
            Log.d(TAG, "Unknown mimeType: " + mimeType + " " + mediaFormat);
        }
        final ArrayList<byte[]> csdList = new ArrayList<>(2);
        ByteBuffer csd0 = mediaFormat.getByteBuffer("csd-0");
        appendByteBuffer(csd0, csdList);
        ByteBuffer csd1 = mediaFormat.getByteBuffer("csd-1");
        appendByteBuffer(csd1, csdList);
        builder.setInitializationData(csdList);
        return builder.build();
    }

    private static void appendByteBuffer(ByteBuffer csd, List<byte[]> list) {
        if (csd != null) {
            byte[] buffer = new byte[csd.limit()];
            csd.rewind();
            csd.get(buffer);
            list.add(buffer);
        }
    }
    /**
     * How long we let MediaCodec idle before we shut it down
     */
    private static final long MEDIA_CODEC_IDLE_MS = 1000L;
    /**
     * Microseconds in a second
     */
    private static final int ONE_US = 1_000_000;
    /**
     * Fragment duration.  Directly impacts video lag.
     * Values < 1 second will greatly reduce compressions
     * Values < 0.5 will likely cause jank
     */
    private static final int FRAGMENT_DURATION_US = ONE_US;

    private static final int MAX_CLIENTS = 4;

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

    /**
     * Number of active connections
     * @see {@link CameraViewModel@MEDIA_CODEC_SHUTDOWN}
     */
    public final MutableLiveData<Integer> connectionData = new MutableLiveData<>(MEDIA_CODEC_SHUTDOWN);

    /**
     * Socket address of the server
     */
    public final MutableLiveData<InetSocketAddress> inetSocketAddressData = new MutableLiveData<>();

    private final HandlerThread handlerThread = new HandlerThread("Camera");
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ConnectivityManager connectivityManager;

    private final Executor executor = mainHandler::post;

    private final Semaphore streamSemaphore = new Semaphore(0);

    /**
     * Indicates that the MediaCodec is in the process of shutting down
     */
    private volatile boolean mediaCodecShutdown = false;

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
        private Network currentNetwork;
        @Override
        public void onAvailable(@NonNull Network network) {
            if (jetty == null) {
                InetAddress inetAddress = null;
                LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
                if (linkProperties != null) {
                    for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                        inetAddress = linkAddress.getAddress();
                        if (inetAddress instanceof Inet4Address) {
                            break;
                        }
                    }
                }
                if (inetAddress != null) {
                    InetSocketAddress inetSocketAddress = new InetSocketAddress(inetAddress, 8080);
                    getWorkHandler().post(()->{
                        jetty = new Server(inetSocketAddress);
                        jetty.setHandler(new ServletHandler());
                        try {
                            jetty.start();
                            currentNetwork = network;
                            inetSocketAddressData.postValue(inetSocketAddress);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to start Jetty", e);
                        }
                    });
                }
            }
        }

        @Override
        public void onLost(@NonNull Network network) {
            if (currentNetwork != null && currentNetwork.equals(network)) {
                try {
                    jetty.stop();
                    jetty = null;
                    currentNetwork = null;
                    inetSocketAddressData.postValue(null);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    };

    private final MediaCodec.Callback mediaCodecCallback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            // Should not get called
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {

            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                try {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) > 0) {
                        // Got end of stream, shutdown MediaCodec/Muxer
                        mMp4Muxer.close();
                        mMp4Muxer = null;
                        fragmentServer.close();
                        fragmentServer = null;
                        updateCameraCaptureSession(null);
                        codecSurface.release();
                        codecSurface = null;
                        mediaCodec.stop();
                        restartMediaCodec();
                        configureCamera(cameraDevice, surfaceHolder, codecSurface);
                        mediaCodecShutdown = false;
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
            Log.e(TAG, "MediaCodec Error", e);
        }

        @OptIn(markerClass = UnstableApi.class) @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat mediaFormat) {
            // Hack to deal with in-exact key frame rates
            int fragmentDurationUs = FRAGMENT_DURATION_US >= ONE_US ? (FRAGMENT_DURATION_US - ONE_US / 4) : FRAGMENT_DURATION_US;
            mMp4Muxer = new Mp4Muxer.Builder(fragmentServer = new FragmentServer())
                    .setFragmentedMp4Enabled(true)
                    .setFragmentDurationUs(fragmentDurationUs)
                    .build();
            final Format format = getFormat(mediaFormat);
            mMp4Muxer.setOrientation(sensorRotation);

            trackToken = mMp4Muxer.addTrack(0, format);
            streamSemaphore.release(4);
        }
    };

    final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (streamSemaphore.availablePermits() == MAX_CLIENTS) {
                // If we have no clients, initiate a shutdown of MediaCodec/Muxer
                streamSemaphore.drainPermits();
                connectionData.postValue(MEDIA_CODEC_SHUTDOWN);
                mediaCodecShutdown = true;
                mediaCodec.signalEndOfInputStream();
            }
        }
    };
    /**
     * Preferred display size
     */
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

    @Nullable
    private MediaCodec mediaCodec;

    private MediaFormat mediaFormat;

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

    private void restartMediaCodec() {
        mediaCodec.setCallback(mediaCodecCallback, getWorkHandler());
        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codecSurface = mediaCodec.createInputSurface();
        mediaCodec.start();
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
                            restartMediaCodec();
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

    @NonNull
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
        updateCameraCaptureSession(streamSemaphore.availablePermits() > 0 ? this.codecSurface : null);
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
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setHeader("content-type", "video/mp4");

            if (!streamSemaphore.tryAcquire()) {
                if (mediaCodecShutdown) {
                    Log.e(TAG, "Connection while in middle of shutdown");
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    response.setHeader(HttpHeaders.RETRY_AFTER, "1");
                    return;
                } else if (fragmentServer != null) {
                    // We are overrun
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    response.setHeader(HttpHeaders.RETRY_AFTER, "60");
                    return;
                } else {
                    updateCameraCaptureSession(codecSurface);
                    try {
                        streamSemaphore.acquire();
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Waiting for server start", e);
                    }
                }
            }
            connectionData.postValue(MAX_CLIENTS - streamSemaphore.availablePermits());
            try (OutputStream out = response.getOutputStream()){
                out.write(fragmentServer.getHeader().array());
                ByteBuffer byteBuffer = null;
                while (true) {
                    byteBuffer = fragmentServer.getFragment(byteBuffer);
                    Log.d(TAG, "Sending " + byteBuffer.remaining());
                    out.write(byteBuffer.array());
                    out.flush();
                }
            } catch (InterruptedException | ClosedChannelException e) {
                // Just ignore this, it's normal
            } finally {
                streamSemaphore.release();
                connectionData.postValue(MAX_CLIENTS - streamSemaphore.availablePermits());
                final Handler workHandler = getWorkHandler();
                workHandler.removeCallbacks(idleCheck);
                workHandler.postDelayed(idleCheck, MEDIA_CODEC_IDLE_MS);
            }
            Log.d(TAG, "Closed");
        }
    }
}
