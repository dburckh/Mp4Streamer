package com.homesoft.muxer;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.util.UnstableApi;

import android.Manifest;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.TextView;

import java.util.ArrayList;

@UnstableApi public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = MainActivity.class.getSimpleName();

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
    private AutoFixSurfaceView surfaceView;

    private TextView url;
    private TextView clients;

    private CameraViewModel cameraViewModel;

    private final ActivityResultLauncher<String[]> mGetCameraPermissions = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), o -> {
        cameraViewModel.init();
        resizeSurface();
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        cameraViewModel = new ViewModelProvider(this).get(CameraViewModel.class);
        url = findViewById(R.id.url);
        clients = findViewById(R.id.clients);

        cameraViewModel.inetSocketAddressData.observe(this, inetSocketAddress -> {
            final String text;
            if (inetSocketAddress == null) {
                text = getString(R.string.noWiFi);
            } else {
                text = "http://" + inetSocketAddress.getAddress().getHostAddress() + ":" + inetSocketAddress.getPort();
            }
            url.setText(text);
        });

        cameraViewModel.connectionData.observe(this, connections -> {
            final String text;
            if (connections < 0) {
                text = getString(R.string.idle);
            } else {
                text = connections.toString();
            }
            clients.setText(text);
        });
        surfaceView = findViewById(R.id.surfaceView);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setKeepScreenOn(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        final ArrayList<String> list = new ArrayList<>(2);
        if (!cameraViewModel.hasCameraPermission()) {
            list.add(Manifest.permission.CAMERA);
        }
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
//            list.add(Manifest.permission.RECORD_AUDIO);
//        }
        if (list.isEmpty()) {
            cameraViewModel.init();
            resizeSurface();
        } else {
            mGetCameraPermissions.launch(list.toArray(new String[0]));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        surfaceView.getHolder().removeCallback(this);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        cameraViewModel.setSurfaceHolder(holder);
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged: (" + width +"x" +height + ")");
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        cameraViewModel.setSurfaceHolder(null);
    }

    private void resizeSurface() {
        Size size = cameraViewModel.getImageSize();
        if (size != null) {
            int deviceOrientation = getWindowManager().getDefaultDisplay().getRotation();

            // Get device orientation in degrees
            // We use front camera, so negate
            int deviceRotation = -ORIENTATIONS.get(deviceOrientation);
            int sensorRotation = cameraViewModel.getSensorRotation();

            // Calculate desired JPEG orientation relative to camera orientation to make
            // the image upright relative to the device orientation
            int rotation = (sensorRotation + deviceRotation + 360) % 360;
            final Size displaySize;
            if (rotation == 90 || rotation == 270) {
                displaySize = new Size(size.getHeight(), size.getWidth());
            } else {
                displaySize = size;
            }
            surfaceView.setAspectRatio(displaySize.getWidth(), displaySize.getHeight());
            final SurfaceHolder surfaceHolder = surfaceView.getHolder();
            if (surfaceHolder != null) {
                Size imageSize = cameraViewModel.getImageSize();
                surfaceHolder.setFixedSize(imageSize.getWidth(), imageSize.getHeight());
            }
        }
    }
}