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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.RelativeLayout;

import com.github.barteksc.pdfviewer.listener.OnAnnotationLinkTapListener;
import com.github.barteksc.pdfviewer.listener.OnDrawListener;
import com.github.barteksc.pdfviewer.listener.OnErrorListener;
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener;
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener;
import com.github.barteksc.pdfviewer.listener.OnPageScrollListener;
import com.github.barteksc.pdfviewer.listener.OnZoomListener;
import com.github.barteksc.pdfviewer.listener.OnRenderListener;
import com.github.barteksc.pdfviewer.model.PagePart;
import com.github.barteksc.pdfviewer.scroll.ScrollHandle;
import com.github.barteksc.pdfviewer.source.AssetSource;
import com.github.barteksc.pdfviewer.source.ByteArraySource;
import com.github.barteksc.pdfviewer.source.DocumentSource;
import com.github.barteksc.pdfviewer.source.FileSource;
import com.github.barteksc.pdfviewer.source.InputStreamSource;
import com.github.barteksc.pdfviewer.source.UriSource;
import com.github.barteksc.pdfviewer.util.ArrayUtils;
import com.github.barteksc.pdfviewer.util.Constants;
import com.github.barteksc.pdfviewer.util.MathUtils;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * It supports animations, zoom, cache, and swipe.
 * <p>
 * To fully understand this class you must know its principles :
 * - The PDF document is seen as if we always want to draw all the pages.
 * - The thing is that we only draw the visible parts.
 * - All parts are the same size, this is because we can't interrupt a native page rendering,
 * so we need these renderings to be as fast as possible, and be able to interrupt them
 * as soon as we can.
 * - The parts are loaded when the current offset or the current zoom level changes
 * <p>
 * Important :
 * - DocumentPage = A page of the PDF document.
 * - UserPage = A page as defined by the user.
 * By default, they're the same. But the user can change the pages order
 * using {@link #load(DocumentSource, String, OnLoadCompleteListener, OnErrorListener, int[])}. In this
 * particular case, a userPage of 5 can refer to a documentPage of 17.
 */
public class PDFView extends RelativeLayout {

    private static final String TAG = PDFView.class.getSimpleName();
    private static PdfiumCore pdfiumCore;
    private static final PDFViewDocumentLoader documentLoader = new PDFViewDocumentLoader();

    public static final float DEFAULT_MAX_SCALE = 3.0f;
    public static final float DEFAULT_MID_SCALE = 1.75f;
    public static final float DEFAULT_MIN_SCALE = 1.0f;

    private float minZoom = DEFAULT_MIN_SCALE;
    private float midZoom = DEFAULT_MID_SCALE;
    private float maxZoom = DEFAULT_MAX_SCALE;

    /**
     * START - scrolling in first page direction
     * END - scrolling in last page direction
     * NONE - not scrolling
     */
    enum ScrollDir {
        NONE, START, END
    }

    private ScrollDir scrollDir = ScrollDir.NONE;

    /**
     * Rendered parts go to the cache manager
     */
    CacheManager cacheManager;

    /**
     * Animation manager manage all offset and zoom animation
     */
    private AnimationManager animationManager;

    /**
     * Drag manager manage all touch events
     */
    private DragPinchManager dragPinchManager;

    /**
     * Document decoding task
     */
    private PDFViewDocumentLoader.DocumentDecodingTask decodingTask;

    /**
     * The pages the user want to display in order
     * (ex: 0, 2, 2, 8, 8, 1, 1, 1)
     */
    private int[] originalUserPages;

    /**
     * The same pages but with a filter to avoid repetition
     * (ex: 0, 2, 8, 1)
     */
    private int[] filteredUserPages;

    /**
     * The same pages but with a filter to avoid repetition
     * (ex: 0, 1, 1, 2, 2, 3, 3, 3)
     */
    private int[] filteredUserPageIndexes;

    /**
     * Number of pages in the loaded PDF document
     */
    private int documentPageCount;

    /**
     * The index of the current sequence
     */
    private int currentPage;

    /**
     * The index of the current sequence
     */
    private int currentFilteredPage;

    /**
     * The actual width and height of the pages in the PDF document
     */
    private int pageWidth, pageHeight;

    /**
     * The optimal width and height of the pages to fit the component size
     */
    private float optimalPageWidth, optimalPageHeight;

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    private float currentXOffset = 0;

    /**
     * If you picture all the pages side by side in their optimal width,
     * and taking into account the zoom level, the current offset is the
     * position of the left border of the screen in this big picture
     */
    private float currentYOffset = 0;

    private float pageMarginHorizontal = 0;

    private float pageMarginVertical = 0;

    /**
     * The zoom level, always >= 1
     */
    private float zoom = 1f;

    /**
     * True if the PDF is zooming (zoom has been started but not ended)
     */
    private boolean isZooming = false;

    /**
     * True if the PDFView has been recycled
     */
    private boolean recycled = true;

    /**
     * Current state of the view
     */
    private State state = State.DEFAULT;

    /**
     * The thread {@link #primaryRenderingHandler} will run on
     */
    private HandlerThread primaryRenderingThread;

    /**
    * Handler always waiting in the background and rendering tasks
    */
    RenderingHandler primaryRenderingHandler;

    /**
     * The thread {@link #secondaryRenderingHandler} will run on
     */
    private HandlerThread secondaryRenderingThread;

    /**
     * Handler always waiting in the background and rendering tasks
     */
    RenderingHandler secondaryRenderingHandler;

    private PagesLoader pagesLoader;

    /**
     * Call back object to call when the PDF is loaded
     */
    private OnLoadCompleteListener onLoadCompleteListener;

    private OnErrorListener onErrorListener;

    /**
     * Call back object to call when the page has changed
     */
    private OnPageChangeListener onPageChangeListener;

    /**
     * Call back object to call when the page is scrolled
     */
    private OnPageScrollListener onPageScrollListener;

    /**
     * Callback object to call before and after the page is zoomed
     */
    private OnZoomListener onZoomListener;

    /**
     * Call back object to call when the above layer is to drawn
     */
    private OnDrawListener onDrawListener;

    /**
     * Call back object to call when the document is initially rendered
     */
    private OnRenderListener onRenderListener;

    /**
     * Callback object to call when user taps an annotation link
     */
    private OnAnnotationLinkTapListener onAnnotationLinkTapListener;

    /**
     * Paint object for drawing
     */
    private Paint paint;

    /**
     * Paint object for drawing debug stuff
     */
    private Paint debugPaint;

    private int defaultPage = 0;

    /**
     * True if should scroll through pages vertically instead of horizontally
     */
    private boolean swipeVertical = true;

    private DocumentSource documentSource;

    private PdfDocument pdfDocument;

    private ScrollHandle scrollHandle;

    private boolean isScrollHandleInit = false;

    ScrollHandle getScrollHandle() {
        return scrollHandle;
    }

    /**
     * True if bitmap should use ARGB_8888 format and take more memory
     * False if bitmap should be compressed by using RGB_565 format and take less memory
     */
    private boolean bestQuality = false;

    /**
     * True if annotations should be rendered
     * False otherwise
     */
    private boolean annotationRendering = false;

    /**
     * True if the view should render during scaling<br/>
     * Can not be forced on older API versions (< Build.VERSION_CODES.KITKAT) as the GestureDetector does
     * not detect scrolling while scaling.<br/>
     * False otherwise
     */
    private boolean renderDuringScale = false;

    /**
     * Antialiasing and bitmap filtering
     */
    private boolean enableAntialiasing = true;

    private PaintFlagsDrawFilter antialiasFilter =
            new PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private boolean enablePaging = true;

    /**
     * Draw preview only
     */
    private boolean previewOnly = false;

    /**
     * Construct the initial view
     */
    public PDFView(Context context, AttributeSet set) {
        super(context, set);

        if (isInEditMode()) {
            return;
        }

        cacheManager = CacheManager.create();
        animationManager = new AnimationManager(this);
        dragPinchManager = new DragPinchManager(this, animationManager);

        paint = new Paint();
        debugPaint = new Paint();
        debugPaint.setStyle(Style.STROKE);

        if (pdfiumCore == null) {
            pdfiumCore = new PdfiumCore(context.getResources().getDisplayMetrics().densityDpi);
        }

        setWillNotDraw(false);
    }

    public PdfiumCore getPdfiumCore() {
        return this.pdfiumCore;
    }

    public PdfDocument getPdfDocument() {
        return this.pdfDocument;
    }

    private void load(DocumentSource docSource, String password, OnLoadCompleteListener listener, OnErrorListener onErrorListener) {
        load(docSource, password, listener, onErrorListener, null);
    }

    private void load(DocumentSource docSource, String password, OnLoadCompleteListener onLoadCompleteListener, OnErrorListener onErrorListener, int[] userPages) {

        if (!recycled) {
            throw new IllegalStateException("Don't call load on a PDF View without recycling it first.");
        }

        // Manage UserPages if not null
        if (userPages != null) {
            this.originalUserPages = userPages;
            this.filteredUserPages = ArrayUtils.deleteDuplicatedPages(originalUserPages);
            this.filteredUserPageIndexes = ArrayUtils.calculateIndexesInDuplicateArray(originalUserPages);
        }

        this.documentSource = docSource;
        this.onLoadCompleteListener = onLoadCompleteListener;
        this.onErrorListener = onErrorListener;

        int firstPageIdx = 0;
        if (originalUserPages != null) {
            firstPageIdx = originalUserPages[0];
        }

        recycled = false;

        // Start decoding document
        decodingTask = documentLoader.load(docSource, password, this, pdfiumCore, firstPageIdx);
    }

    /**
     * Go to the given page.
     *
     * @param page Page index.
     */
    public void jumpTo(int page, boolean withAnimation) {
        if (swipeVertical) {
            float toY = getPageOffsetY(page);
            if (withAnimation) {
                animationManager.startYAnimation(currentYOffset, toY);
            } else {
                moveTo(currentXOffset, toY);
            }
        } else {
            float toX = getPageOffsetX(page);
            if (withAnimation) {
                animationManager.startXAnimation(currentXOffset, toX);
            } else {
                moveTo(toX, currentYOffset);
                showPage(page);
            }
        }

    }

    public void jumpTo(int page) {
        jumpTo(page, false);
    }

    public float getPageOffsetX(int page) {
        return -page * getPageOuterWidth() - (getPageOuterWidth()-getWidth())/2;
    }

    public float getPageOffsetY(int page) {
        return -page * getPageOuterHeight() - (getPageOuterHeight()-getHeight())/2;
    }

    public int getPageByOffset(float offsetX, float offsetY) {
        float offset;
        float contentSize, containerSize;
        if (swipeVertical) {
            offset = offsetY;
            contentSize = getPageOuterHeight();
            containerSize = getHeight();
        } else {
            offset = offsetX;
            contentSize = getPageOuterWidth();
            containerSize = getWidth();
        }

        return Math.min(
                getPageCount()-1,
                Math.max(
                        0,
                        (int) Math.floor((Math.abs(offset) + containerSize / 5) / contentSize)));
    }

    void showPage(int pageNb) {
        if (recycled) {
            return;
        }

        // Check the page number and makes the
        // difference between UserPages and DocumentPages
        pageNb = determineValidPageNumberFrom(pageNb);
        currentPage = pageNb;
        currentFilteredPage = pageNb;
        if (filteredUserPageIndexes != null) {
            if (pageNb >= 0 && pageNb < filteredUserPageIndexes.length) {
                pageNb = filteredUserPageIndexes[pageNb];
                currentFilteredPage = pageNb;
            }
        }

        loadPages();

        if (scrollHandle != null && !documentFitsView()) {
            scrollHandle.setPageNum(currentPage + 1);
        }

        if (onPageChangeListener != null) {
            onPageChangeListener.onPageChanged(currentPage, getPageCount());
        }
    }

    /**
     * Get current position as ratio of document length to visible area.
     * 0 means that document start is visible, 1 that document end is visible
     *
     * @return offset between 0 and 1
     */
    public float getPositionOffset() {
        float offset;
        if (swipeVertical) {
            offset = -currentYOffset / (getPageCount() * toCurrentScale(optimalPageHeight) - getHeight());
        } else {
            offset = -currentXOffset / (getPageCount() * toCurrentScale(optimalPageWidth) - getWidth());
        }
        return MathUtils.limit(offset, 0, 1);
    }

    /**
     * @param progress   must be between 0 and 1
     * @param moveHandle whether to move scroll handle
     * @see PDFView#getPositionOffset()
     */
    public void setPositionOffset(float progress, boolean moveHandle) {
        if (swipeVertical) {
            moveTo(currentXOffset, (-getPageCount() * toCurrentScale(optimalPageHeight) + getHeight()) * progress, moveHandle);
        } else {
            moveTo((-getPageCount() * toCurrentScale(optimalPageWidth) + getWidth()) * progress, currentYOffset, moveHandle);
        }
        loadPageByOffset();
    }

    public void setPositionOffset(float progress) {
        setPositionOffset(progress, true);
    }

    public void stopFling() {
        animationManager.stopFling();
    }

    public int getPageCount() {
        if (originalUserPages != null) {
            return originalUserPages.length;
        }
        return documentPageCount;
    }

    public void enableSwipe(boolean enableSwipe) {
        dragPinchManager.setSwipeEnabled(enableSwipe);
    }

    public void enableDoubletap(boolean enableDoubletap) {
        this.dragPinchManager.enableDoubletap(enableDoubletap);
    }

    public void enableFling(boolean enableFling) {
        dragPinchManager.setFlingEnabled(enableFling);
    }

    public void enableZoom(boolean enableZoom) {
        dragPinchManager.setZoomEnabled(enableZoom);
    }

    public void enableGestures(boolean enableGestures) {
        dragPinchManager.setEnabled(enableGestures);
    }

    private void setOnPageChangeListener(OnPageChangeListener onPageChangeListener) {
        this.onPageChangeListener = onPageChangeListener;
    }

    OnPageChangeListener getOnPageChangeListener() {
        return this.onPageChangeListener;
    }

    private void setOnPageScrollListener(OnPageScrollListener onPageScrollListener) {
        this.onPageScrollListener = onPageScrollListener;
    }

    OnPageScrollListener getOnPageScrollListener() {
        return this.onPageScrollListener;
    }

    private void setOnZoomListener(OnZoomListener onZoomListener) {
        this.onZoomListener = onZoomListener;
    }

    OnZoomListener getOnZoomListener() {
        return this.onZoomListener;
    }

    private void setOnRenderListener(OnRenderListener onRenderListener) {
        this.onRenderListener = onRenderListener;
    }

    private void setOnAnnotationLinkTapListener(OnAnnotationLinkTapListener onLinkTapListener) {
        this.onAnnotationLinkTapListener = onLinkTapListener;
    }

    OnRenderListener getOnRenderListener() {
        return this.onRenderListener;
    }

    private void setOnDrawListener(OnDrawListener onDrawListener) {
        this.onDrawListener = onDrawListener;
    }

    public void recycle() {
        animationManager.stopAll();

        // Stop tasks
        if (primaryRenderingThread != null) {
            primaryRenderingThread.quit();
        }

        if (secondaryRenderingThread != null) {
            secondaryRenderingThread.quit();
        }

        if (primaryRenderingHandler != null) {
            primaryRenderingHandler.cancel();
            primaryRenderingHandler.stop();
        }

        if (secondaryRenderingHandler != null) {
            secondaryRenderingHandler.cancel();
            secondaryRenderingHandler.stop();
        }

        if (decodingTask != null) {
            decodingTask.requestCancel();
        }

        // Clear caches
        cacheManager.recycle();

        if (scrollHandle != null && isScrollHandleInit) {
            scrollHandle.destroyLayout();
        }

        if (documentSource != null) {
            documentLoader.unload(documentSource, pdfiumCore);
        }

        primaryRenderingThread = null;
        documentSource = null;
        primaryRenderingHandler = null;
        originalUserPages = null;
        filteredUserPages = null;
        filteredUserPageIndexes = null;
        pdfDocument = null;
        scrollHandle = null;
        isScrollHandleInit = false;
        currentXOffset = currentYOffset = 0;
        zoom = 1f;
        recycled = true;
        state = State.DEFAULT;
    }

    public boolean isRecycled() {
        return recycled;
    }

    @Override
    protected void onDetachedFromWindow() {
        recycle();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (isInEditMode() || state != State.LOADED) {
            return;
        }
        animationManager.stopAll();
        calculateOptimalWidthAndHeight();
        loadPages();
        if (swipeVertical)
            moveTo(currentXOffset, calculateCenterOffsetForPage(currentFilteredPage));
        else
            moveTo(calculateCenterOffsetForPage(currentFilteredPage), currentYOffset);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (isInEditMode()) {
            return;
        }

        // As I said in this class javadoc, we can think of this canvas as a huge
        // strip on which we draw all the images. We actually only draw the rendered
        // parts, of course, but we render them in the place they belong in this huge
        // strip.

        // That's where Canvas.translate(x, y) becomes very helpful.
        // This is the situation :
        //  _______________________________________________
        // |   			 |					 			   |
        // | the actual  |					The big strip  |
        // |	canvas	 | 								   |
        // |_____________|								   |
        // |_______________________________________________|
        //
        // If the rendered part is on the bottom right corner of the strip
        // we can draw it but we won't see it because the canvas is not big enough.

        // But if we call translate(-X, -Y) on the canvas just before drawing the object :
        //  _______________________________________________
        // |   			  					  _____________|
        // |   The big strip     			 |			   |
        // |		    					 |	the actual |
        // |								 |	canvas	   |
        // |_________________________________|_____________|
        //
        // The object will be on the canvas.
        // This technique is massively used in this method, and allows
        // abstraction of the screen position when rendering the parts.

        // Draws background

        if (enableAntialiasing) {
            canvas.setDrawFilter(antialiasFilter);
        }

        Drawable bg = getBackground();
        if (bg == null) {
            canvas.drawColor(Color.WHITE);
        } else {
            bg.draw(canvas);
        }

        if (recycled) {
            return;
        }

        if (state != State.SHOWN) {
            return;
        }

        // Moves the canvas before drawing any element
        float currentXOffset = this.currentXOffset + pageMarginHorizontal;
        float currentYOffset = this.currentYOffset + pageMarginVertical;
        canvas.translate(currentXOffset, currentYOffset);

        // Draws thumbnails
        for (PagePart part : cacheManager.getThumbnails()) {
            drawPart(canvas, part);
        }

        // Draws parts
        if (!this.previewOnly) {
            for (PagePart part : cacheManager.getPageParts()) {
                drawPart(canvas, part);
            }
        }

        // Draws the user layer
        if (onDrawListener != null) {
            canvas.translate(toCurrentScale(currentFilteredPage * optimalPageWidth), 0);

            onDrawListener.onLayerDrawn(canvas, //
                    toCurrentScale(optimalPageWidth), //
                    toCurrentScale(optimalPageHeight),
                    currentPage);

            canvas.translate(-toCurrentScale(currentFilteredPage * optimalPageWidth), 0);
        }

        // Restores the canvas position
        canvas.translate(-currentXOffset, -currentYOffset);
    }

    /**
     * Draw a given PagePart on the canvas
     */
    private void drawPart(Canvas canvas, PagePart part) {
        // Can seem strange, but avoid lot of calls
        RectF pageRelativeBounds = part.getPageRelativeBounds();
        Bitmap renderedBitmap = part.getRenderedBitmap();

        if (renderedBitmap.isRecycled()) {
            return;
        }

        // Move to the target page
        float localTranslationX = 0;
        float localTranslationY = 0;
        if (swipeVertical) {
            localTranslationY = part.getUserPage() * getPageOuterHeight();
        } else {
            localTranslationX = part.getUserPage() * getPageOuterWidth();
        }
        canvas.translate(localTranslationX, localTranslationY);

        Rect srcRect = new Rect(0, 0, renderedBitmap.getWidth(),
                renderedBitmap.getHeight());

        float offsetX = toCurrentScale(pageRelativeBounds.left * optimalPageWidth);
        float offsetY = toCurrentScale(pageRelativeBounds.top * optimalPageHeight);
        float width = toCurrentScale(pageRelativeBounds.width() * optimalPageWidth);
        float height = toCurrentScale(pageRelativeBounds.height() * optimalPageHeight);

        // If we use float values for this rectangle, there will be
        // a possible gap between page parts, especially when
        // the zoom level is high.
        RectF dstRect = new RectF(
                (int) offsetX,
                (int) offsetY,
                (int) (offsetX + width),
                (int) (offsetY + height));

        // Check if bitmap is in the screen
        float translationX = currentXOffset + localTranslationX + pageMarginHorizontal;
        float translationY = currentYOffset + localTranslationY + pageMarginVertical;
        if (translationX + dstRect.left >= getWidth() || translationX + dstRect.right <= 0 ||
                translationY + dstRect.top >= getHeight() || translationY + dstRect.bottom <= 0) {
            canvas.translate(-localTranslationX, -localTranslationY);
            return;
        }

        canvas.drawBitmap(renderedBitmap, srcRect, dstRect, paint);

        if (Constants.DEBUG_MODE) {
            debugPaint.setColor(part.getUserPage() % 2 == 0 ? Color.RED : Color.BLUE);
            canvas.drawRect(dstRect, debugPaint);
        }

        // Restore the canvas position
        canvas.translate(-localTranslationX, -localTranslationY);

    }

    /**
     * Load all the parts around the center of the screen,
     * taking into account X and Y offsets, zoom level, and
     * the current page displayed
     */
    public void loadPages() {
        if (optimalPageWidth == 0 || optimalPageHeight == 0 || primaryRenderingHandler == null) {
            return;
        }

        // Cancel all current tasks
        primaryRenderingHandler.cancel();
        cacheManager.makeANewSet();

        pagesLoader.loadPages();
        redraw();
    }

    /**
     * Called when the PDF is loaded
     */
    void loadComplete(PdfDocument pdfDocument, int pageWidth, int pageHeight) {
        state = State.LOADED;
        this.documentPageCount = pdfiumCore.getPageCount(pdfDocument);

        this.pdfDocument = pdfDocument;

        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        calculateOptimalWidthAndHeight();

        pagesLoader = new PagesLoader(this);

        primaryRenderingThread = new HandlerThread("PDF primary rendering");
        primaryRenderingThread.setPriority(Thread.MAX_PRIORITY);
        primaryRenderingHandler = initRenderingHandler(primaryRenderingThread);

        secondaryRenderingThread = new HandlerThread("PDF secondary rendering");
        secondaryRenderingHandler = initRenderingHandler(secondaryRenderingThread);

        if (scrollHandle != null) {
            scrollHandle.setupLayout(this);
            isScrollHandleInit = true;
        }

        if (onLoadCompleteListener != null) {
            onLoadCompleteListener.loadComplete(documentPageCount);
        }

        jumpTo(defaultPage, false);
    }

    RenderingHandler initRenderingHandler(HandlerThread renderingThread) {

        if (!this.getPreviewOnly()) {
            renderingThread.setPriority(Thread.MAX_PRIORITY);
        }

        if (!renderingThread.isAlive()) {
            renderingThread.start();
        }

        if (primaryRenderingHandler != null) {
            primaryRenderingHandler.cancel();
            primaryRenderingHandler.stop();
        }

        primaryRenderingHandler = new RenderingHandler(renderingThread.getLooper(),
                this, pdfiumCore, pdfDocument);
        primaryRenderingHandler.start();
        return primaryRenderingHandler;
    }

    void loadError(Throwable t) {
        state = State.ERROR;
        recycle();
        invalidate();
        if (this.onErrorListener != null) {
            this.onErrorListener.onError(t);
        } else {
            Log.e("PDFView", "load pdf error", t);
        }
    }

    void redraw() {
        invalidate();
    }

    /**
     * Called when a rendering task is over and
     * a PagePart has been freshly created.
     *
     * @param part The created PagePart.
     */
    public void onBitmapRendered(PagePart part) {
        // when it is first rendered part
        boolean triggerInitialRender = false;
        if (state == State.LOADED) {
            state = State.SHOWN;
            triggerInitialRender = true;
        }

        if (part.isThumbnail()) {
            cacheManager.cacheThumbnail(part);
        } else {
            cacheManager.cachePart(part);
        }

        redraw();

        if (triggerInitialRender && onRenderListener != null) {
            onRenderListener.onInitiallyRendered(getPageCount(), optimalPageWidth, optimalPageHeight);
        }
    }

    /**
     * Given the UserPage number, this method restrict it
     * to be sure it's an existing page. It takes care of
     * using the user defined pages if any.
     *
     * @param userPage A page number.
     * @return A restricted valid page number (example : -2 => 0)
     */
    private int determineValidPageNumberFrom(int userPage) {
        if (userPage <= 0) {
            return 0;
        }
        if (originalUserPages != null) {
            if (userPage >= originalUserPages.length) {
                return originalUserPages.length - 1;
            }
        } else {
            if (userPage >= documentPageCount) {
                return documentPageCount - 1;
            }
        }
        return userPage;
    }

    /**
     * Calculate the x/y-offset needed to have the given
     * page centered on the screen. It doesn't take into
     * account the zoom level.
     *
     * @param pageNb The page number.
     * @return The x/y-offset to use to have the pageNb centered.
     */
    private float calculateCenterOffsetForPage(int pageNb) {
        if (swipeVertical) {
            float imageY = -(pageNb * optimalPageHeight);
            imageY += getHeight() / 2 - optimalPageHeight / 2;
            return imageY;
        } else {
            float imageX = -(pageNb * optimalPageWidth);
            imageX += getWidth() / 2 - optimalPageWidth / 2;
            return imageX;
        }
    }

    /**
     * Calculate the optimal width and height of a page
     * considering the area width and height
     */
    private void calculateOptimalWidthAndHeight() {
        if (state == State.DEFAULT || getWidth() == 0) {
            return;
        }

        float maxWidth = getWidth(), maxHeight = getHeight();
        float w = pageWidth, h = pageHeight;
        float ratio = w / h;
        w = maxWidth;
        h = (float) Math.floor(maxWidth / ratio);
        if (h > maxHeight) {
            h = maxHeight;
            w = (float) Math.floor(maxHeight * ratio);
        }

        optimalPageWidth = w;
        optimalPageHeight = h;

    }

    public void moveTo(float offsetX, float offsetY) {
        moveTo(offsetX, offsetY, true);
    }

    /**
     * Move to the given X and Y offsets, but check them ahead of time
     * to be sure not to go outside the the big strip.
     *
     * @param offsetX    The big strip X offset to use as the left border of the screen.
     * @param offsetY    The big strip Y offset to use as the right border of the screen.
     * @param moveHandle whether to move scroll handle or not
     */
    public void moveTo(float offsetX, float offsetY, boolean moveHandle) {
        if (swipeVertical) {
            // Check X offset
            if (toCurrentScale(optimalPageWidth) < getWidth()) {
                offsetX = getWidth() / 2 - toCurrentScale(optimalPageWidth) / 2;
            } else {
                if (offsetX > 0) {
                    offsetX = 0;
                } else if (offsetX + toCurrentScale(optimalPageWidth) < getWidth()) {
                    offsetX = getWidth() - toCurrentScale(optimalPageWidth);
                }
            }

            // Check Y offset
            if (getPageCount() * toCurrentScale(optimalPageHeight) < getHeight()) { // whole document height visible on screen
                offsetY = (getHeight() - getPageCount() * toCurrentScale(optimalPageHeight)) / 2;
            } else if (isPaging() && isZooming()) { // limit to current page bounds when zooming and paging
                offsetY = Math.max(
                        (-(currentPage+1) * toCurrentScale(optimalPageHeight) + getHeight()),
                        Math.min(-currentPage * toCurrentScale(optimalPageHeight), offsetY));
            } else {
                if (offsetY > 0) { // top visible
                    offsetY = 0;
                } else if (offsetY + toCurrentScale(getPageCount() * optimalPageHeight) < getHeight()) { // bottom visible
                    offsetY = -toCurrentScale(getPageCount() * optimalPageHeight) + getHeight();
                }
            }

            if (offsetY < currentYOffset) {
                scrollDir = ScrollDir.END;
            } else if (offsetY > currentYOffset) {
                scrollDir = ScrollDir.START;
            } else {
                scrollDir = ScrollDir.NONE;
            }
        } else {
            // Check Y offset
            if (toCurrentScale(optimalPageHeight) < getHeight()) {
                offsetY = getHeight() / 2 - toCurrentScale(optimalPageHeight) / 2;
            } else {
                if (offsetY > 0) {
                    offsetY = 0;
                } else if (offsetY + toCurrentScale(optimalPageHeight) < getHeight()) {
                    offsetY = getHeight() - toCurrentScale(optimalPageHeight);
                }
            }

            // Check X offset
            if (getPageCount() * toCurrentScale(optimalPageWidth) < getWidth()) { // whole document width visible on screen
                offsetX = (getWidth() - getPageCount() * toCurrentScale(optimalPageWidth)) / 2;
            } else if (isPaging() && isZooming()) { // limit to current page bounds when zooming and paging
                offsetX = Math.max(
                        (-(currentPage+1) * toCurrentScale(optimalPageWidth) + getWidth()),
                        Math.min(-currentPage * toCurrentScale(optimalPageWidth), offsetX));
            } else {
                if (offsetX > 0) { // left visible
                    offsetX = 0;
                } else if (offsetX + toCurrentScale(getPageCount() * optimalPageWidth) < getWidth()) { // right visible
                    offsetX = -toCurrentScale(getPageCount() * optimalPageWidth) + getWidth();
                }
            }

            if (offsetX < currentXOffset) {
                scrollDir = ScrollDir.END;
            } else if (offsetX > currentXOffset) {
                scrollDir = ScrollDir.START;
            } else {
                scrollDir = ScrollDir.NONE;
            }
        }

        pageMarginHorizontal = (isPaging() && getPageCount() > 1) ? toCurrentScale(getWidth() - optimalPageWidth) / 2 : 0;
        pageMarginVertical = (isPaging() && getPageCount() > 1) ? toCurrentScale(getHeight() - optimalPageHeight) / 2 : 0;

        currentXOffset = offsetX;
        currentYOffset = offsetY;
        float positionOffset = getPositionOffset();

        if (moveHandle && scrollHandle != null && !documentFitsView()) {
            scrollHandle.setScroll(positionOffset);
        }

        if (onPageScrollListener != null) {
            onPageScrollListener.onPageScrolled(getCurrentPage(), positionOffset);
        }

        redraw();
    }

    ScrollDir getScrollDir() {
        return scrollDir;
    }

    void loadPageByOffset() {
        int page = getPageByOffset(currentXOffset, currentYOffset);

        if (page >= 0 && page <= getPageCount() - 1 && page != getCurrentPage()) {
            showPage(page);
        } else {
            loadPages();
        }
    }

    int[] getFilteredUserPages() {
        return filteredUserPages;
    }

    int getDocumentPageCount() {
        return documentPageCount;
    }

    /**
     * Move relatively to the current position.
     *
     * @param dx The X difference you want to apply.
     * @param dy The Y difference you want to apply.
     * @see #moveTo(float, float)
     */
    public void moveRelativeTo(float dx, float dy) {
        moveTo(currentXOffset + dx, currentYOffset + dy);
    }

    /**
     * Change the zoom level
     */
    public void zoomTo(float zoom) {
        this.zoom = zoom;
    }

    /**
     * Change the zoom level, relatively to a pivot point.
     * It will call moveTo() to make sure the given point stays
     * in the middle of the screen.
     *
     * Note: calling this method does not trigger zoom start/end
     * callbacks. Remember to call triggerZoomStart and triggerZoomEnd,
     * respectively.
     *
     * @param zoom  The zoom level.
     * @param pivot The point on the screen that should stays.
     */
    public void zoomCenteredTo(float zoom, PointF pivot) {
        float dzoom = zoom / this.zoom;
        zoomTo(zoom);
        float baseX = currentXOffset * dzoom;
        float baseY = currentYOffset * dzoom;
        baseX += (pivot.x - pivot.x * dzoom);
        baseY += (pivot.y - pivot.y * dzoom);
        moveTo(baseX, baseY);
    }

    /**
     * @see #zoomCenteredTo(float, PointF)
     */
    public void zoomCenteredRelativeTo(float dzoom, PointF pivot) {
        zoomCenteredTo(zoom * dzoom, pivot);
    }

    /**
     * Checks if whole document can be displayed on screen, doesn't include zoom
     *
     * @return true if whole document can displayed at once, false otherwise
     */
    public boolean documentFitsView() {
        if (swipeVertical) {
            return getPageCount() * optimalPageHeight < getHeight();
        } else {
            return getPageCount() * optimalPageWidth < getWidth();
        }
    }

    public void fitToWidth(int page) {
        if (state != State.SHOWN) {
            Log.e(TAG, "Cannot fit, document not rendered yet");
            return;
        }
        fitToWidth();
        jumpTo(page);
    }

    public void fitToWidth() {
        if (state != State.SHOWN) {
            Log.e(TAG, "Cannot fit, document not rendered yet");
            return;
        }
        int centerPos = getPageAtPositionOffset(0);
        zoomTo(getWidth() / optimalPageWidth);
        setPositionOffset(centerPos);
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getCurrentDocumentPage() {
        int documentPage = getDocumentPage(getCurrentPage());
        if (documentPage < 0) {
            return 0;
        } else {
            return documentPage;
        }
    }

    public int getDocumentPage(int userPage) {
        int documentPage = userPage;
        if (getFilteredUserPages() != null) {
            if (userPage < 0 || userPage >= getFilteredUserPages().length) {
                return -1;
            } else {
                documentPage = getFilteredUserPages()[userPage];
            }
        }

        if (documentPage < 0 || userPage >= getDocumentPageCount()) {
            return -1;
        }

        return documentPage;
    }

    public float getCurrentXOffset() {
        return currentXOffset;
    }

    public float getCurrentYOffset() {
        return currentYOffset;
    }

    public float toRealScale(float size) {
        return size / zoom;
    }

    public float toCurrentScale(float size) {
        return size * zoom;
    }

    public float getZoom() {
        return zoom;
    }

    public boolean isZooming() {
        return zoom != minZoom;
    }

    public float getOptimalPageWidth() {
        return optimalPageWidth;
    }

    public float getPageOuterWidth() {
        return toCurrentScale(optimalPageWidth) + pageMarginHorizontal*2;
    }

    public float getOptimalPageHeight() {
        return optimalPageHeight;
    }

    public float getPageOuterHeight() {
        return toCurrentScale(optimalPageHeight) + pageMarginVertical*2;
    }

    public float getPageMarginHorizontal() {
        return pageMarginHorizontal;
    }

    public float getPageMarginVertical() {
        return pageMarginVertical;
    }

    private void setDefaultPage(int defaultPage) {
        this.defaultPage = defaultPage;
    }

    public void resetZoom() {
        zoomTo(minZoom);
    }

    public void resetZoomWithAnimation() {
        zoomWithAnimation(minZoom);
    }

    public void zoomWithAnimation(float centerX, float centerY, float scale) {
        animationManager.startZoomAnimation(centerX, centerY, zoom, scale);
    }

    public void zoomWithAnimation(float scale) {
        animationManager.startZoomAnimation(getWidth() / 2, getHeight() / 2, zoom, scale);
    }

    public void triggerZoomStart() {
        if (isZooming) {
            return; // do nothing if already started
        }

        isZooming = true;
        if (onZoomListener != null) {
            onZoomListener.onZoomStart(getZoom());
        }
    }

    public void triggerZoomEnd() {
        isZooming = false;

        if (onZoomListener != null) {
            onZoomListener.onZoomEnd(getZoom());
        }
    }

    public void handleSingleTap(float x, float y) {

        // Convert x, y and content size based on current panoration
        // offsets and zoom level
        float offsetX = getCurrentXOffset();
        float offsetY = getCurrentYOffset();
        float contentX = x - offsetX;
        float contentY = y - offsetY;
        int scaledX = Math.round(contentX);
        int scaledY = Math.round(contentY);
        int contentWidth = Math.round(toCurrentScale(optimalPageWidth));
        int contentHeight = Math.round(toCurrentScale(optimalPageHeight));

        // Convert position (X,Y) from device to page (PDF) coordinate system.
        // In device the origin is in the top-left corner while in
        // the PDF the origin is in the bottom-left corner.
        PdfDocument.Position pos = pdfiumCore.deviceToPage(
                pdfDocument,
                getCurrentDocumentPage(),
                0,
                0,
                contentWidth,
                contentHeight,
                0,
                scaledX,
                scaledY);

        // If the point is within the PDF
        if (pos != null) {
            // Find link from given PDF position
            PdfDocument.Link link = pdfiumCore.getLinkAtPoint(
                    this.pdfDocument, getCurrentDocumentPage(), pos.getX(), pos.getY());

            if (link != null && this.onAnnotationLinkTapListener != null) {
                this.onAnnotationLinkTapListener.onSingleTapLink(link);
            }
        }


    }

    private void setScrollHandle(ScrollHandle scrollHandle) {
        this.scrollHandle = scrollHandle;
    }

    /**
     * Get page number at given offset
     *
     * @param positionOffset scroll offset between 0 and 1
     * @return page number at given offset, starting from 0
     */
    public int getPageAtPositionOffset(float positionOffset) {
        float optimalSize, viewDimension;
        int direction = scrollDir == ScrollDir.END ? 1 : -1;
        if (swipeVertical) {
            optimalSize = toCurrentScale(optimalPageHeight);
            viewDimension = getHeight() * direction;
        } else {
            optimalSize = toCurrentScale(optimalPageWidth);
            viewDimension = getWidth() * direction;
        }

        return (int) Math.floor((getPageCount() * positionOffset) + (viewDimension / 5 / optimalSize));
    }

    public float getMinZoom() {
        return minZoom;
    }

    public void setMinZoom(float minZoom) {
        this.minZoom = minZoom;
    }

    public float getMidZoom() {
        return midZoom;
    }

    public void setMidZoom(float midZoom) {
        this.midZoom = midZoom;
    }

    public float getMaxZoom() {
        return maxZoom;
    }

    public void setMaxZoom(float maxZoom) {
        this.maxZoom = maxZoom;
    }

    public void useBestQuality(boolean bestQuality) {
        this.bestQuality = bestQuality;
    }

    public boolean isBestQuality() {
        return bestQuality;
    }

    public boolean isSwipeVertical() {
        return swipeVertical;
    }

    public void setSwipeVertical(boolean swipeVertical) {
        this.swipeVertical = swipeVertical;
    }

    public void enableAnnotationRendering(boolean annotationRendering) {
        this.annotationRendering = annotationRendering;
    }

    public boolean isAnnotationRendering() {
        return annotationRendering;
    }

    public void enableRenderDuringScale(boolean renderDuringScale) {
        this.renderDuringScale = renderDuringScale;
    }

    public boolean isAntialiasing() {
        return enableAntialiasing;
    }

    public void enableAntialiasing(boolean enableAntialiasing) {
        this.enableAntialiasing = enableAntialiasing;
    }

    public void setPreviewOnly(boolean previewOnly) {
        if (this.previewOnly == previewOnly) {
            return;
        }

        this.previewOnly = previewOnly;

        if (primaryRenderingThread != null) {
            primaryRenderingThread.setPriority(previewOnly ? Thread.NORM_PRIORITY : Thread.MAX_PRIORITY);
        }

        if (previewOnly && primaryRenderingHandler != null) {
            primaryRenderingHandler.cancel();
        }

        if (!previewOnly) {
            loadPages();
        }
    }

    public boolean getPreviewOnly() {
        return this.previewOnly;
    }

    public boolean isPaging() { return enablePaging; };

    public void enablePaging(boolean enablePaging) {
        this.enablePaging = enablePaging;
    }

    public boolean doRenderDuringScale() {
        return renderDuringScale;
    }

    public PdfDocument.Meta getDocumentMeta() {
        if (pdfDocument == null) {
            return null;
        }
        return pdfiumCore.getDocumentMeta(pdfDocument);
    }

    public List<PdfDocument.Bookmark> getTableOfContents() {
        if (pdfDocument == null) {
            return new ArrayList<>();
        }
        return pdfiumCore.getTableOfContents(pdfDocument);
    }

    /**
     * Use an asset file as the pdf source
     */
    public Configurator fromAsset(String assetName) {
        return new Configurator(new AssetSource(assetName));
    }

    /**
     * Use a file as the pdf source
     */
    public Configurator fromFile(File file) {
        return new Configurator(new FileSource(file));
    }

    /**
     * Use URI as the pdf source, for use with content providers
     */
    public Configurator fromUri(Uri uri) {
        return new Configurator(new UriSource(uri));
    }

    /**
     * Use bytearray as the pdf source, documents is not saved
     *
     * @param bytes
     * @return
     */
    public Configurator fromBytes(byte[] bytes) {
        return new Configurator(new ByteArraySource(bytes));
    }

    public Configurator fromStream(InputStream stream) {
        return new Configurator(new InputStreamSource(stream));
    }

    /**
     * Use custom source as pdf source
     */
    public Configurator fromSource(DocumentSource docSource) {
        return new Configurator(docSource);
    }

    private enum State {DEFAULT, LOADED, SHOWN, ERROR}

    public class Configurator {

        private final DocumentSource documentSource;

        private int[] pageNumbers = null;

        private boolean enableSwipe = true;

        private boolean enableDoubletap = true;

        private boolean enableFling = true;

        private boolean enableZoom = true;

        private OnDrawListener onDrawListener;

        private OnLoadCompleteListener onLoadCompleteListener;

        private OnErrorListener onErrorListener;

        private OnPageChangeListener onPageChangeListener;

        private OnPageScrollListener onPageScrollListener;

        private OnZoomListener onZoomListener;

        private OnRenderListener onRenderListener;

        private OnAnnotationLinkTapListener onAnnotationLinkTapListener;

        private int defaultPage = 0;

        private boolean swipeHorizontal = false;

        private boolean annotationRendering = false;

        private String password = null;

        private ScrollHandle scrollHandle = null;

        private boolean antialiasing = true;

        private HandlerThread renderingThread;

        private boolean previewOnly = false;

        private Configurator(DocumentSource documentSource) {
            this.documentSource = documentSource;
        }

        public Configurator pages(int... pageNumbers) {
            this.pageNumbers = pageNumbers;
            return this;
        }

        public Configurator enableSwipe(boolean enableSwipe) {
            this.enableSwipe = enableSwipe;
            return this;
        }

        public Configurator enableDoubletap(boolean enableDoubletap) {
            this.enableDoubletap = enableDoubletap;
            return this;
        }

        public Configurator enableFling(boolean enableFling) {
            this.enableFling = enableFling;
            return this;
        }

        public Configurator enableZoom(boolean enableZoom) {
            this.enableZoom = enableZoom;
            return this;
        }

        public Configurator enableAnnotationRendering(boolean annotationRendering) {
            this.annotationRendering = annotationRendering;
            return this;
        }

        public Configurator onDraw(OnDrawListener onDrawListener) {
            this.onDrawListener = onDrawListener;
            return this;
        }

        public Configurator onLoad(OnLoadCompleteListener onLoadCompleteListener) {
            this.onLoadCompleteListener = onLoadCompleteListener;
            return this;
        }

        public Configurator onPageScroll(OnPageScrollListener onPageScrollListener) {
            this.onPageScrollListener = onPageScrollListener;
            return this;
        }

        public Configurator onZoom(OnZoomListener onZoomListener) {
            this.onZoomListener = onZoomListener;
            return this;
        }

        public Configurator onError(OnErrorListener onErrorListener) {
            this.onErrorListener = onErrorListener;
            return this;
        }

        public Configurator onPageChange(OnPageChangeListener onPageChangeListener) {
            this.onPageChangeListener = onPageChangeListener;
            return this;
        }

        public Configurator onRender(OnRenderListener onRenderListener) {
            this.onRenderListener = onRenderListener;
            return this;
        }

        public Configurator onAnnotationLinkTap(OnAnnotationLinkTapListener onLinkTapListener) {
            this.onAnnotationLinkTapListener = onLinkTapListener;
            return this;
        }

        public Configurator defaultPage(int defaultPage) {
            this.defaultPage = defaultPage;
            return this;
        }

        public Configurator swipeHorizontal(boolean swipeHorizontal) {
            this.swipeHorizontal = swipeHorizontal;
            return this;
        }

        public Configurator password(String password) {
            this.password = password;
            return this;
        }

        public Configurator scrollHandle(ScrollHandle scrollHandle) {
            this.scrollHandle = scrollHandle;
            return this;
        }

        public Configurator enableAntialiasing(boolean antialiasing) {
            this.antialiasing = antialiasing;
            return this;
        }

        public Configurator setPreviewOnly(boolean previewOnly) {
            this.previewOnly = previewOnly;
            return this;
        }

        public void load() {
            PDFView.this.recycle();
            PDFView.this.setOnDrawListener(onDrawListener);
            PDFView.this.setOnPageChangeListener(onPageChangeListener);
            PDFView.this.setOnPageScrollListener(onPageScrollListener);
            PDFView.this.setOnZoomListener(onZoomListener);
            PDFView.this.setOnRenderListener(onRenderListener);
            PDFView.this.setOnAnnotationLinkTapListener(onAnnotationLinkTapListener);
            PDFView.this.enableSwipe(enableSwipe);
            PDFView.this.enableDoubletap(enableDoubletap);
            PDFView.this.enableFling(enableFling);
            PDFView.this.enableZoom(enableZoom);
            PDFView.this.setDefaultPage(defaultPage);
            PDFView.this.setSwipeVertical(!swipeHorizontal);
            PDFView.this.enableAnnotationRendering(annotationRendering);
            PDFView.this.setScrollHandle(scrollHandle);
            PDFView.this.enableAntialiasing(antialiasing);
            PDFView.this.dragPinchManager.setSwipeVertical(swipeVertical);
            if (pageNumbers != null) {
                PDFView.this.load(documentSource, password, onLoadCompleteListener, onErrorListener, pageNumbers);
            } else {
                PDFView.this.load(documentSource, password, onLoadCompleteListener, onErrorListener);
            }
        }
    }
}
