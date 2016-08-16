/*
 * Copyright (C) 2013 Peng fei Pan <sky@xiaopan.me>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.xiaopan.sketch.feature;

import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.widget.ImageView;

import me.xiaopan.sketch.SketchImageView;
import me.xiaopan.sketch.feature.zoom.ImageZoomer;

/**
 * ImageView缩放功能
 */
// TODO: 16/8/14 根据图片实际大小调整三级缩放比例
public class ImageZoomFunction extends SketchImageView.Function {
    private ImageView imageView;

    private ImageZoomer imageZoomer;
    private boolean fromSuperLargeImageFunction;

    public ImageZoomFunction(ImageView imageView) {
        this.imageView = imageView;
        init();
    }

    private void init() {
        if (imageZoomer != null) {
            imageZoomer.cleanup();
        }
        imageZoomer = new ImageZoomer(imageView, true);
    }

    @Override
    public void onAttachedToWindow() {
        init();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return imageZoomer.onTouch(imageView, event);
    }

    @Override
    public boolean onDetachedFromWindow() {
        imageZoomer.cleanup();
        return false;
    }

    @Override
    public boolean onDrawableChanged(String callPosition, Drawable oldDrawable, Drawable newDrawable) {
        imageZoomer.update();
        return false;
    }

    public ImageView.ScaleType getScaleType() {
        return imageZoomer.getScaleType();
    }

    @Override
    public void setScaleType(ImageView.ScaleType scaleType) {
        super.setScaleType(scaleType);
        imageZoomer.setScaleType(scaleType);
    }

    public void destroy() {

    }

    public ImageZoomer getImageZoomer() {
        return imageZoomer;
    }

    public boolean isFromSuperLargeImageFunction() {
        return fromSuperLargeImageFunction;
    }

    public void setFromSuperLargeImageFunction(boolean fromSuperLargeImageFunction) {
        this.fromSuperLargeImageFunction = fromSuperLargeImageFunction;
    }
}