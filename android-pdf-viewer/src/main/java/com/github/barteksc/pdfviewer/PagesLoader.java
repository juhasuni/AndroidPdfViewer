package com.github.barteksc.pdfviewer;


import android.graphics.RectF;
import android.util.Pair;

import com.github.barteksc.pdfviewer.util.Constants;
import com.github.barteksc.pdfviewer.util.MathUtils;

import static com.github.barteksc.pdfviewer.util.Constants.Cache.CACHE_SIZE;

class PagesLoader {

    private PDFView pdfView;

    // variables set on every call to loadPages()
    private int cacheOrder;
    private float scaledHeight;
    private float scaledWidth;
    private Pair<Integer, Integer> colsRows;
    private float xOffset;
    private float yOffset;
    private float rowHeight;
    private float colWidth;
    private float pageRelativePartWidth;
    private float pageRelativePartHeight;
    private float partRenderWidth;
    private float partRenderHeight;
    private int thumbnailWidth;
    private int thumbnailHeight;
    private final RectF thumbnailRect = new RectF(0, 0, 1, 1);
    private boolean previewOnly;

    private class Holder {
        int page;
        int row;
        int col;
    }

    public PagesLoader(PDFView pdfView) {
        this.pdfView = pdfView;
    }

    private Pair<Integer, Integer> getPageColsRows() {
        float ratioX = 1f / pdfView.getOptimalPageWidth();
        float ratioY = 1f / pdfView.getOptimalPageHeight();
        final float partHeight = (Constants.PART_SIZE * ratioY) / pdfView.getZoom();
        final float partWidth = (Constants.PART_SIZE * ratioX) / pdfView.getZoom();
        final int nbRows = MathUtils.ceil(1f / partHeight);
        final int nbCols = MathUtils.ceil(1f / partWidth);
        return new Pair<>(nbCols, nbRows);
    }

    private int documentPage(int userPage) {
        return pdfView.getDocumentPage(userPage);
    }

    private Holder getPageAndCoordsByOffset(float offset) {
        Holder holder = new Holder();
        boolean isVertical = pdfView.isSwipeVertical();
        float col, row;
        float scaledPageSize = isVertical ? scaledHeight : scaledWidth;
        float pageSize = isVertical ? pdfView.getOptimalPageHeight() : pdfView.getOptimalPageWidth();
        int page = MathUtils.floor(offset / scaledPageSize);
        float margin = isVertical ? pdfView.getPageMarginVertical() : pdfView.getPageMarginHorizontal();

        holder.page = (MathUtils.ceil(page) - page < 0.00001) ? MathUtils.ceil(page) : MathUtils.floor(page);

        float startPos = scaledPageSize * holder.page + margin;
        float endPos = startPos + pdfView.toCurrentScale(pageSize);
        float pagePos = Math.min(endPos, offset) - startPos;

        if (isVertical) {
            if (pagePos > 0) {
                row = pagePos / rowHeight;
            } else {
                row = 0;
            }
            col = xOffset / colWidth;
        } else {
            if (pagePos > 0) {
                col = pagePos / colWidth;
            } else {
                col = 0;
            }
            row = yOffset / rowHeight;
        }

        holder.col = (MathUtils.ceil(col) - col < 0.00001) ? MathUtils.ceil(col) : MathUtils.floor(col);
        holder.row = (MathUtils.ceil(row) - row < 0.00001) ? MathUtils.ceil(row) : MathUtils.floor(row);

        return holder;
    }

    private void loadThumbnail(int userPage, int documentPage, boolean usingPrimaryHandler) {
        if (!pdfView.cacheManager.containsThumbnail(userPage, documentPage,
                thumbnailWidth, thumbnailHeight, thumbnailRect)) {
            RenderingHandler handler = usingPrimaryHandler ? pdfView.primaryRenderingHandler : pdfView.secondaryRenderingHandler;
            handler.addRenderingTask(userPage, documentPage,
                    thumbnailWidth, thumbnailHeight, thumbnailRect,
                    true, 0, pdfView.isBestQuality(), pdfView.isAnnotationRendering());
        }
    }

    /**
     * @param number if < 0 then row (column) is above view, else row (column) is visible or below view
     * @return
     */
    private int loadRelative(int number, int nbOfPartsLoadable, boolean outsideView) {
        boolean isVertical = pdfView.isSwipeVertical();
        float newOffset;
        float itemSize = isVertical ? rowHeight : colWidth;
        float itemsTotalSize = itemSize * number;
        float screenSize = isVertical ? pdfView.getHeight() : pdfView.getWidth();
        float offset = isVertical ? yOffset : xOffset;
        float pageOuterWidth = isVertical ? scaledHeight : scaledWidth;
        newOffset = offset + (outsideView ? screenSize : 0) + itemsTotalSize;

        // Outside the stripe, there's nothing to render
        if (newOffset >= pdfView.getPageCount() * pageOuterWidth) {
            return 0;
        }

        Holder holder = getPageAndCoordsByOffset(newOffset);
        return loadStripe(holder, nbOfPartsLoadable);
    }

    private int loadStripe(Holder holder, int nbOfPartsLoadable) {
        int loaded = 0;
        int documentPage = documentPage(holder.page);
        if (documentPage < 0) {
            return 0;
        }

        boolean isVertical = pdfView.isSwipeVertical();
        int max = isVertical ? colsRows.first : colsRows.second;
        int first = isVertical ? holder.col : holder.row; // first visible row/col
        int stripeLength = isVertical ? pdfView.getWidth() : pdfView.getHeight();
        float itemLength = isVertical ? colWidth : rowHeight;
        int itemsPerScreen = MathUtils.ceil(stripeLength / itemLength);
        int last = first + itemsPerScreen; // + one extra row/col
        last = Math.min(last, max);

        for (int i = first; i <= last; i++) {
            if (loadCell(holder.page, documentPage, (isVertical ? holder.row : i), (isVertical ? i : holder.col), pageRelativePartWidth, pageRelativePartHeight)) {
                loaded++;
            }
            if (loaded >= nbOfPartsLoadable) {
                return loaded;
            }
        }

        return loaded;
    }

    public int loadVisible() {
        int parts = 0;
        int loadedThumbs = 0;
        Holder firstHolder, lastHolder;
        boolean isVertical = pdfView.isSwipeVertical();
        float offset = isVertical ? yOffset : xOffset;
        int pageSize = isVertical ? pdfView.getHeight() : pdfView.getWidth();
        float optimalPageSize = isVertical ? pdfView.getOptimalPageHeight() : pdfView.getOptimalPageWidth();

        firstHolder = getPageAndCoordsByOffset(offset);
        lastHolder = getPageAndCoordsByOffset(offset + pageSize - 1);
        float stripeLength = isVertical ? rowHeight : colWidth;

        int stripesPerPage = MathUtils.ceil(pdfView.toCurrentScale(optimalPageSize) / stripeLength);

        for (int p = firstHolder.page; p <= lastHolder.page; p++) {

            int documentPage = documentPage(p);
            if (documentPage >= 0) {
                loadThumbnail(p, documentPage, true);
                loadedThumbs++;
            }

            if (previewOnly) {
                continue;
            }

            int first = 0;

            if (p == lastHolder.page) {
                stripesPerPage = lastHolder.col + 1;
            }

            if (p == firstHolder.page) {
                first = firstHolder.col;
            }

            for (int i = first; i < stripesPerPage && parts < CACHE_SIZE; i++) {
                Holder holder = new Holder();
                holder.page = p;
                holder.row = isVertical ? i : firstHolder.row;
                holder.col = isVertical ? firstHolder.col : i;
                parts += loadStripe(holder, CACHE_SIZE - parts);
            }
        }

        int deltaMax = Math.max(
                pdfView.getPageCount() - lastHolder.page,
                firstHolder.page-1);

        for (int d = 1; d <= deltaMax; d++) {
            int prev = firstHolder.page - d;
            int next = lastHolder.page + d;

            if (loadedThumbs >= Constants.Cache.THUMBNAILS_CACHE_SIZE) {
                break;
            }

            if (prev >= 0) {
                loadThumbnail(prev, documentPage(prev), false);
                loadedThumbs++;
            }

            if (loadedThumbs >= Constants.Cache.THUMBNAILS_CACHE_SIZE) {
                break;
            }

            if (next < pdfView.getPageCount()) {
                loadThumbnail(next, documentPage(next), false);
                loadedThumbs++;
            }
        }

        return parts;
    }

    private boolean loadCell(int userPage, int documentPage, int row, int col, float pageRelativePartWidth, float pageRelativePartHeight) {

        float relX = pageRelativePartWidth * col;
        float relY = pageRelativePartHeight * row;
        float relWidth = pageRelativePartWidth;
        float relHeight = pageRelativePartHeight;

        // Adjust width and height to
        // avoid being outside the page
        float renderWidth = partRenderWidth;
        float renderHeight = partRenderHeight;
        if (relX + relWidth > 1) {
            relWidth = 1 - relX;
        }
        if (relY + relHeight > 1) {
            relHeight = 1 - relY;
        }
        renderWidth *= relWidth;
        renderHeight *= relHeight;
        RectF pageRelativeBounds = new RectF(relX, relY, relX + relWidth, relY + relHeight);

        if (renderWidth > 0 && renderHeight > 0) {
            if (!pdfView.cacheManager.upPartIfContained(userPage, documentPage, renderWidth, renderHeight, pageRelativeBounds, cacheOrder)) {
                pdfView.primaryRenderingHandler.addRenderingTask(userPage, documentPage,
                        renderWidth, renderHeight, pageRelativeBounds, false, cacheOrder,
                        pdfView.isBestQuality(), pdfView.isAnnotationRendering());
            }

            cacheOrder++;
            return true;
        }
        return false;
    }

    public void loadPages() {
        previewOnly = pdfView.getPreviewOnly();
        scaledHeight = pdfView.getPageOuterHeight();
        scaledWidth = pdfView.getPageOuterWidth();
        thumbnailWidth = (int) (pdfView.getOptimalPageWidth() * Constants.THUMBNAIL_RATIO);
        thumbnailHeight = (int) (pdfView.getOptimalPageHeight() * Constants.THUMBNAIL_RATIO);
        colsRows = getPageColsRows();
        xOffset = -MathUtils.max(pdfView.getCurrentXOffset(), 0);
        yOffset = -MathUtils.max(pdfView.getCurrentYOffset(), 0);
        rowHeight = pdfView.toCurrentScale(pdfView.getOptimalPageHeight()) / colsRows.second;
        colWidth = pdfView.toCurrentScale(pdfView.getOptimalPageWidth()) / colsRows.first;
        pageRelativePartWidth = 1f / (float) colsRows.first;
        pageRelativePartHeight = 1f / (float) colsRows.second;
        partRenderWidth = Constants.PART_SIZE / pageRelativePartWidth;
        partRenderHeight = Constants.PART_SIZE / pageRelativePartHeight;
        cacheOrder = 1;
        int loaded = loadVisible();
        if (pdfView.getZoom() == 1 && pdfView.getPageCount() > 1 && !previewOnly) {
            if (pdfView.getScrollDir().equals(PDFView.ScrollDir.END)) { // if scrolling to end, preload next view
                for (int i = 0; i < Constants.PRELOAD_COUNT && loaded < CACHE_SIZE; i++) {
                    loaded += loadRelative(i, loaded, true);
                }
            } else if (pdfView.getScrollDir().equals(PDFView.ScrollDir.START)) { // if scrolling to start, preload previous view
                for (int i = -1; i > -loaded-Constants.PRELOAD_COUNT && loaded < CACHE_SIZE; i--) {
                    loaded += loadRelative(i, loaded, false);
                }
            }
        }

    }
}
