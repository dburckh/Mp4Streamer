package com.homesoft.muxer;

import static com.homesoft.muxer.FragmentServer.ONE_US;

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
import androidx.annotation.UiThread;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.media3.common.util.UnstableApi;

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
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

@UnstableApi public class CameraViewModel extends AndroidViewModel implements SurfaceEncoder.Listener {
    /**
     * Connection value indicating that MediaCodec is shutdown and needs to be restarted
     */
    public static final int MEDIA_CODEC_SHUTDOWN = -1;

    /**
     * How long we let MediaCodec idle before we shut it down
     */
    private static final long MEDIA_CODEC_IDLE_MS = 1000L;
    /**
     * Fragment duration.  Directly impacts video lag.
     * Values < 1 second will greatly reduce compressions
     * Values < 0.5 will likely cause jank
     */
    private static final int FRAGMENT_DURATION_US = ONE_US;

    private static final int MAX_CLIENTS = 4;

    private static final String TAG = CameraViewModel.class.getSimpleName();

    private static int getPixels(Size size) {
        return size.getWidth() * size.getHeight();
    }

    /**
     * Number of active connections
     * or {@link CameraViewModel#MEDIA_CODEC_SHUTDOWN} if shutdown
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

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            if (surfaceHolder != null) {
                configureCamera(cameraDevice, surfaceHolder, null);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice = null;
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

    final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (streamSemaphore.availablePermits() == MAX_CLIENTS) {
                // If we have no clients, initiate a shutdown of MediaCodec/Muxer
                streamSemaphore.drainPermits();
                connectionData.postValue(MEDIA_CODEC_SHUTDOWN);
                if (surfaceEncoder != null) {
                    surfaceEncoder.shutdown();
                }
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
    private CameraDevice cameraDevice;

    CameraCaptureSession.StateCallback configureCallback;

    private Size imageSize;
    private int sensorRotation;
    private Range<Integer> selectedFps;

    @Nullable
    private SurfaceEncoder surfaceEncoder;

    @Nullable
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
                            surfaceEncoder = new SurfaceEncoder(imageSize.getWidth(), imageSize.getHeight(), selectedFps.getUpper(),
                                    FRAGMENT_DURATION_US / ONE_US, this);
                        } catch (IOException e) {
                            Log.e(TAG, "failed to create SurfaceMuxer", e);
                        }
                        cameraManager.openCamera(id, cameraStateCallback, mainHandler);
                        break;
                    }
                }

            } catch (CameraAccessException e) {
                Log.e(TAG, "Debug", e);
            }
        }
        return surfaceEncoder != null;
    }

    @NonNull
    @UiThread
    private Handler getWorkHandler() {
        if (workHandler == null) {
            workHandler = new Handler(handlerThread.getLooper());
        }
        return workHandler;
    }

    public Size getImageSize() {
        return imageSize;
    }

    public int getSensorRotation() {
        return sensorRotation;
    }

    private void configureCamera(@NonNull CameraDevice cameraDevice,
                                 @NonNull SurfaceHolder surfaceHolder,
                                 @Nullable Surface codecSurface) {
        try {
            configureCallback = new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    try {
                        CaptureRequest.Builder builder = cameraDevice
                                .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedFps);
                        builder.addTarget(surfaceHolder.getSurface());
                        if (codecSurface != null) {
                            builder.addTarget(codecSurface);
                        }

                        session.setRepeatingRequest(builder.build(), null, null);

                    } catch (CameraAccessException e) {
                        Log.e(TAG, "onConfigured()", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    // TODO: Handle
                }
            };
            ArrayList<Surface> surfaceList = new ArrayList<>(2);
            surfaceList.add(surfaceHolder.getSurface());
            if (codecSurface != null) {
                surfaceList.add(codecSurface);
            }
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
            if (cameraDevice != null && surfaceHolder != null) {
                configureCamera(cameraDevice, surfaceHolder, null);
            }
        }
    }


    @Override
    protected void onCleared() {
        super.onCleared();
        if (surfaceEncoder != null) {
            surfaceEncoder.release();
        }
        connectivityManager.unregisterNetworkCallback(networkCallback);
    }

    @Override
    public void onShutdown() {
        if (fragmentServer != null) {
            fragmentServer.close();
            fragmentServer = null;
        }
    }

    @Override
    public void onReady(MediaFormat mediaFormat) {
        fragmentServer = new FragmentServer(mediaFormat, sensorRotation, FRAGMENT_DURATION_US);
        streamSemaphore.release(4);
    }

    @Override
    public void onBuffer(ByteBuffer byteBuffer, @NonNull MediaCodec.BufferInfo info) {
        if (fragmentServer != null) {
            try {
                fragmentServer.onBuffer(byteBuffer, info);
            } catch (IOException e) {
                Log.wtf(TAG, "Failed to write buffer", e);
            }
        }
    }

    class ServletHandler extends AbstractHandler {
        private final AtomicInteger sequence = new AtomicInteger(0);
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
            final int seq = sequence.getAndIncrement();
            Log.d(TAG, "handle() Got connection: " + seq);
            if (surfaceEncoder == null ||
                    surfaceEncoder.getState() == SurfaceEncoder.State.RELEASED ||
                    cameraDevice == null || surfaceHolder == null) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                Log.w(TAG, "handle() encoder not ready");
                return;
            }

            if (!streamSemaphore.tryAcquire()) {
                if (fragmentServer == null) {
                    switch (surfaceEncoder.getState()) {
                        case IDLE -> {
                            Surface surface = surfaceEncoder.startMediaCodec(workHandler);
                            configureCamera(cameraDevice, surfaceHolder, surface);
                        }
                        case STOPPING -> {
                            // Edge case where where a client connects while we are shutting down
                            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                            response.setHeader(HttpHeaders.RETRY_AFTER, "1");
                            return;
                        }
                    }
                    try {
                        //Log.d(TAG, "handle() Waiting for server start... " + seq);
                        streamSemaphore.acquire();
                        //Log.d(TAG, "handle() Server Started: " + seq);
                    } catch (InterruptedException e) {
                        Log.e(TAG, "handle() Waiting for server start", e);
                        return;
                    }
                } else {
                    Log.e(TAG, "handle() Too many sessions: " + seq);
                    // We are overrun
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    response.setHeader(HttpHeaders.RETRY_AFTER, "60");
                    return;
                }
            }
            response.setHeader("content-type", "video/mp4");

            connectionData.postValue(MAX_CLIENTS - streamSemaphore.availablePermits());
            try (OutputStream out = response.getOutputStream()){
                out.write(fragmentServer.getHeader().array());
                ByteBuffer byteBuffer = null;
                while (true) {
                    byteBuffer = fragmentServer.getFragment(byteBuffer);
                    //Log.d(TAG, "Sending " + byteBuffer.remaining());
                    out.write(byteBuffer.array());
                    out.flush();
                }
            } catch (Exception e) {
                // Just ignore this, it's normal
                //Log.d(TAG, "handle() exception", e);
            } finally {
                streamSemaphore.release();
                connectionData.postValue(MAX_CLIENTS - streamSemaphore.availablePermits());
                final Handler workHandler = getWorkHandler();
                workHandler.removeCallbacks(idleCheck);
                workHandler.postDelayed(idleCheck, MEDIA_CODEC_IDLE_MS);
            }
            Log.d(TAG, "handle() Closed: " + seq);
        }
    }
}
