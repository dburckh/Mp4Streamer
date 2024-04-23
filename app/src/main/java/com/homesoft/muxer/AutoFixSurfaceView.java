package com.homesoft.muxer;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceView;

public class AutoFixSurfaceView extends SurfaceView {
    private static final String TAG = AutoFixSurfaceView.class.getSimpleName();

    public AutoFixSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AutoFixSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    private float aspectRatio;

    public void setAspectRatio(int width, int height) {
        aspectRatio = width / (float)height;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        //super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (aspectRatio == 0f) {
            setMeasuredDimension(width, height);
        } else {
            int newWidth;
            int newHeight;

            if (width > height) {
                newWidth = (int)(height * aspectRatio);
                newHeight = height;
            } else {
                newWidth = width;
                newHeight = (int)(width / aspectRatio);
            }

            Log.d(TAG, "Measured dimensions set: " + newWidth + " x " + newHeight);
            setMeasuredDimension(newWidth, newHeight);
        }
    }
}
