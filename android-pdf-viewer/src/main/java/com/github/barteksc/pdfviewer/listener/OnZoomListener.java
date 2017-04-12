package com.github.barteksc.pdfviewer.listener;

/*
 * This interface allows an extern class to listen
 * to zoom start and end. Both callbacks receive the
 * current zoom level.
 */
public interface OnZoomListener {

    /**
     * @param zoom current zoom level (zoom level before zooming)
     */
    public void onZoomStart(float zoom);

    /**
     *
     * @param zoom current zoom level (zoom level after zooming)
     */
    public void onZoomEnd(float zoom);
}
