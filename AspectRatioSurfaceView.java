package com.my.mealse.filemanager.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;

/**
 * SurfaceView, который сохраняет пропорции видео.
 * Видеоплеер через setAspectRatio() сообщает фактические размеры —
 * View сам подгоняет layout.
 */
public class AspectRatioSurfaceView extends SurfaceView {

    private int videoWidth = 0;
    private int videoHeight = 0;

    public AspectRatioSurfaceView(Context context) {
        super(context);
    }

    public AspectRatioSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectRatioSurfaceView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setAspectRatio(int width, int height) {
        if (width != videoWidth || height != videoHeight) {
            this.videoWidth = width;
            this.videoHeight = height;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (videoWidth > 0 && videoHeight > 0) {
            float videoRatio = (float) videoWidth / videoHeight;
            float viewRatio = (float) width / height;

            if (viewRatio > videoRatio) {
                // Экран шире видео → ограничиваем по высоте
                int newWidth = (int) (height * videoRatio);
                setMeasuredDimension(newWidth, height);
            } else {
                // Экран уже видео → ограничиваем по ширине
                int newHeight = (int) (width / videoRatio);
                setMeasuredDimension(width, newHeight);
            }
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}