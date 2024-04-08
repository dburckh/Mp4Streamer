package com.homesoft.muxer;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;

import android.Manifest;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@UnstableApi public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = MainActivity.class.getSimpleName();
    private AutoFixSurfaceView surfaceView;

    private Button stream;

    private TextView url;

    private CameraViewModel cameraViewModel;

    Executor executor = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String[]> mGetMp4 = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
        if (uri != null) {
            executor.execute(new Remuxer(getBaseContext(), uri));
        }
    });

    private final ActivityResultLauncher<String[]> mGetCameraPermissions = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), o -> {
        cameraViewModel.init();
        resizeSurface();
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        cameraViewModel = new ViewModelProvider.AndroidViewModelFactory(getApplication()).create(CameraViewModel.class);
        stream = findViewById(R.id.stream);
        stream.setOnClickListener((v)-> {
            InetSocketAddress inetSocketAddress;
            if (cameraViewModel.isStreaming()) {
                cameraViewModel.stopStream();
                inetSocketAddress = null;
            } else {
                inetSocketAddress = cameraViewModel.startStream();
            }
            updateButton(inetSocketAddress);
        });
        url = findViewById(R.id.url);
        updateButton(null);
        surfaceView = findViewById(R.id.surfaceView);
        surfaceView.getHolder().addCallback(this);
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
        Size size = cameraViewModel.getDisplaySize(this);
        if (size != null) {
            ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams)surfaceView.getLayoutParams();
            String ratio = size.getWidth() + ":" + size.getHeight();
            if (!ratio.equals(lp.dimensionRatio)) {
                Log.d(TAG, "resizeSurfaceView: " + ratio);
                lp.dimensionRatio = ratio;
                surfaceView.setLayoutParams(lp);
                surfaceView.setAspectRatio(size.getWidth(), size.getHeight());
            }
        }
    }

    @Nullable
    private void updateButton(InetSocketAddress inetSocketAddress) {
        String text = cameraViewModel.isStreaming() ? "Stop" : "Stream";
        stream.setText(text);
        if (inetSocketAddress != null) {
            url.setVisibility(View.VISIBLE);
            url.setText("http:/" + inetSocketAddress);
        } else {
            url.setVisibility(View.INVISIBLE);
        }
    }
}