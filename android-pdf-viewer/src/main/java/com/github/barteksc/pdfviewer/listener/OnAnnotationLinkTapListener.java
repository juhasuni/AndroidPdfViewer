package com.github.barteksc.pdfviewer.listener;

import com.shockwave.pdfium.PdfDocument;

/*
 * This interface allows an extern class to listen
 * to single-tap event on annotation link.
 */
public interface OnAnnotationLinkTapListener {

    /**
     * @param link PDF annotation link
     */
    void onSingleTapLink(PdfDocument.Link link);
}
