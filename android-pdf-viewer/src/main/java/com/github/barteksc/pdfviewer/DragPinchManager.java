/**
 * Copyright 2016 Bartosz Schiller
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.barteksc.pdfviewer;

import android.graphics.PointF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import com.github.barteksc.pdfviewer.scroll.ScrollHandle;

import static com.github.barteksc.pdfviewer.util.Constants.Pinch.MAXIMUM_ZOOM;
import static com.github.barteksc.pdfviewer.util.Constants.Pinch.MINIMUM_ZOOM;

/**
 * This Manager takes care of moving the PDFView,
 * set its zoom track user actions.
 */
class DragPinchManager implements GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, ScaleGestureDetector.OnScaleGestureListener, View.OnTouchListener {

    static float MIN_FLING_PAGING_VELOCITY = 4000;

    private PDFView pdfView;
    private AnimationManager animationManager;
    private GestureDetector gestureDetector;
    private ScaleGestureDetector scaleGestureDetector;

    private boolean isSwipeEnabled;
    private boolean swipeVertical;
    private boolean scrolling = false;
    private boolean scaling = false;
    private int currentPage = -1;

    public DragPinchManager(PDFView pdfView, AnimationManager animationManager) {
        this.pdfView = pdfView;
        this.animationManager = animationManager;
        this.isSwipeEnabled = false;
        this.swipeVertical = pdfView.isSwipeVertical();
        gestureDetector = new GestureDetector(pdfView.getContext(), this);
        scaleGestureDetector = new ScaleGestureDetector(pdfView.getContext(), this);
        pdfView.setOnTouchListener(this);
    }

    public void enableDoubletap(boolean enableDoubletap) {
        if (enableDoubletap) {
            gestureDetector.setOnDoubleTapListener(this);
        } else {
            gestureDetector.setOnDoubleTapListener(null);
        }
    }

    public boolean isZooming() {
        return pdfView.isZooming();
    }

    private boolean isPageChange(float distance) {
        return Math.abs(distance) > Math.abs(swipeVertical ? pdfView.getPageOuterHeight() : pdfView.getPageOuterWidth() / 2);
    }

    public void setSwipeEnabled(boolean isSwipeEnabled) {
        this.isSwipeEnabled = isSwipeEnabled;
    }

    public void setSwipeVertical(boolean swipeVertical) {
        this.swipeVertical = swipeVertical;
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        ScrollHandle ps = pdfView.getScrollHandle();
        if (ps != null && !pdfView.documentFitsView()) {
            if (!ps.shown()) {
                ps.show();
            } else {
                ps.hide();
            }
        }

        pdfView.handleSingleTap(e.getX(), e.getY());

        pdfView.performClick();
        return true;
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (pdfView.getZoom() < pdfView.getMidZoom()) {
            pdfView.zoomWithAnimation(e.getX(), e.getY(), pdfView.getMidZoom());
        } else if (pdfView.getZoom() < pdfView.getMaxZoom()) {
            pdfView.zoomWithAnimation(e.getX(), e.getY(), pdfView.getMaxZoom());
        } else {
            pdfView.resetZoomWithAnimation();
        }
        return true;
    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onDown(MotionEvent e) {
        animationManager.stopFling();
        return true;
    }

    @Override
    public void onShowPress(MotionEvent e) {

    }

    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        scrolling = true;
        if (currentPage < 0) {
            currentPage = pdfView.getCurrentPage();
        }

        if (isZooming() || isSwipeEnabled) {
            pdfView.moveRelativeTo(-distanceX, -distanceY);
        }

        if ((!scaling && isZooming()) || (scaling && pdfView.doRenderDuringScale())) {
          pdfView.loadPageByOffset();
        }
        
        return true;
    }

    public void onScrollEnd(MotionEvent event) {
        currentPage = -1;
        pdfView.loadPages();
        hideHandle();
    }

    @Override
    public void onLongPress(MotionEvent e) {

    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        int xOffset = (int) pdfView.getCurrentXOffset();
        int yOffset = (int) pdfView.getCurrentYOffset();
        float minX = xOffset * (swipeVertical ? 2 : pdfView.getPageCount()+1);
        float minY = yOffset * (swipeVertical ? pdfView.getPageCount()+1 : 2);
        float maxX = 0, maxY = 0;

        if (pdfView.isPaging() && !pdfView.isZooming()) {
            float velocity = swipeVertical ? velocityY : velocityX;
            boolean forward = velocity < 0;

            if (Math.abs(velocity) > MIN_FLING_PAGING_VELOCITY) {
                int page = Math.min(pdfView.getPageCount()-1, Math.max(0, forward ? currentPage+1 : currentPage-1));

                if (page != currentPage) {
                    pdfView.jumpTo(page, true);
                    return true;
                }
            }
        }

        animationManager.startFlingAnimation(
                xOffset,
                yOffset,
                (int) velocityX,
                (int) velocityY,
                (int) minX,
                (int) maxX,
                (int) minY,
                (int) maxY);

        return true;
    }

    @Override
    public boolean onScale(ScaleGestureDetector detector) {
        float dr = detector.getScaleFactor();
        float wantedZoom = pdfView.getZoom() * dr;
        if (wantedZoom < MINIMUM_ZOOM) {
            dr = MINIMUM_ZOOM / pdfView.getZoom();
        } else if (wantedZoom > MAXIMUM_ZOOM) {
            dr = MAXIMUM_ZOOM / pdfView.getZoom();
        }
        pdfView.zoomCenteredRelativeTo(dr, new PointF(detector.getFocusX(), detector.getFocusY()));
        return true;
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
        pdfView.triggerZoomStart();
        scaling = true;
        return true;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {
        pdfView.loadPages();
        hideHandle();
        scaling = false;
        pdfView.triggerZoomEnd();
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        boolean retVal = scaleGestureDetector.onTouchEvent(event);
        retVal = gestureDetector.onTouchEvent(event) || retVal;

        if (event.getAction() == MotionEvent.ACTION_UP) {
            if (scrolling) {
                scrolling = false;
                onScrollEnd(event);
            }
        }
        return retVal;
    }

    private void hideHandle() {
        if (pdfView.getScrollHandle() != null && pdfView.getScrollHandle().shown()) {
            pdfView.getScrollHandle().hideDelayed();
        }
    }
}
