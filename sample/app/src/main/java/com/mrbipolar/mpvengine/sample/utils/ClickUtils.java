package com.mrbipolar.mpvengine.sample.utils;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;

public class ClickUtils {

    @SuppressLint("ClickableViewAccessibility")
    public static void applyTouchEffect(View view, View.OnClickListener onClickListener) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.95f).scaleY(0.95f).alpha(0.8f).setDuration(100).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(100).start();
                    break;
            }
            return false; // Return false to allow OnClickListener to trigger
        });

        if (onClickListener != null) {
            view.setOnClickListener(onClickListener);
        }
    }
}