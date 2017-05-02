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

import android.graphics.RectF;
import android.support.annotation.Nullable;

import com.github.barteksc.pdfviewer.model.PagePart;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;

import static com.github.barteksc.pdfviewer.util.Constants.Cache.CACHE_SIZE;
import static com.github.barteksc.pdfviewer.util.Constants.Cache.THUMBNAILS_CACHE_SIZE;

class CacheManager {

    private final PagePartCache passiveCache;

    private final PagePartCache activeCache;

    private final List<PagePart> thumbnails;

    public CacheManager() {
        activeCache = new PagePartCache(CACHE_SIZE);
        passiveCache = new PagePartCache(CACHE_SIZE);
        thumbnails = new ArrayList<>();
    }

    public void cachePart(PagePart part) {
        // If cache too big, remove and recycle
        makeAFreeSpace();

        // Then add part
        activeCache.offer(part);
    }

    public void makeANewSet() {
        passiveCache.appendAll(activeCache);
        activeCache.clear();
    }

    private void makeAFreeSpace() {
        passiveCache.free((activeCache.size() + passiveCache.size()) - CACHE_SIZE);
        activeCache.free((activeCache.size() + passiveCache.size()) - CACHE_SIZE);
    }

    public void cacheThumbnail(PagePart part) {
        synchronized (thumbnails) {
            // If cache too big, remove and recycle
            if (thumbnails.size() >= THUMBNAILS_CACHE_SIZE) {
                thumbnails.remove(0).getRenderedBitmap().recycle();
            }

            // Then add thumbnail
            thumbnails.add(part);
        }

    }

    public boolean upPartIfContained(int userPage, int page, float width, float height, RectF pageRelativeBounds, int toOrder) {
        PagePart fakePart = new PagePart(userPage, page, null, width, height, pageRelativeBounds, false, 0);

        PagePart found;
        if ((found = passiveCache.find(fakePart)) != null) {
            passiveCache.remove(found);
            found.setCacheOrder(toOrder);
            activeCache.offer(found);
            return true;
        }

        return activeCache.find(fakePart) != null;
    }

    /**
     * Return true if already contains the described PagePart
     */
    public boolean containsThumbnail(int userPage, int page, float width, float height, RectF pageRelativeBounds) {
        PagePart fakePart = new PagePart(userPage, page, null, width, height, pageRelativeBounds, true, 0);
        synchronized (thumbnails) {
            for (PagePart part : thumbnails) {
                if (part.equals(fakePart)) {
                    return true;
                }
            }
            return false;
        }
    }

    public List<PagePart> getPageParts() {
        List<PagePart> parts = new ArrayList<>(passiveCache);
        parts.addAll(activeCache);

        return parts;
    }

    public List<PagePart> getThumbnails() {
        synchronized (thumbnails) {
            return thumbnails;
        }
    }

    public void recycle() {
        passiveCache.recycle();
        activeCache.recycle();

        synchronized (thumbnails) {
            for (PagePart part : thumbnails) {
                part.getRenderedBitmap().recycle();
            }
            thumbnails.clear();
        }
    }

    class PagePartCache extends PriorityBlockingQueue<PagePart> {
        PagePartCache(int cacheSize) {
            super(cacheSize, new PagePartComparator());
        }

        void free(int count) {
            // The parts with lowest cache order number get freed first
            for (int i = 0; i < count; i++) {
                PagePart p = poll();
                if (!p.getRenderedBitmap().isRecycled()) {
                    p.getRenderedBitmap().recycle();
                }
            }
        }

        void appendAll(Collection<PagePart> c) {

            // Find the max order number from current parts
            // and use that as the delta for the new parts to
            // ensure that added parts appear last in the priority
            // queue
            int max = 0;
            for (PagePart p : this) {
                max = Math.max(max, p.getCacheOrder());
            }

            for (PagePart p : c) {
                p.setCacheOrder(p.getCacheOrder() + max);
            }

            addAll(c);
        }

        @Nullable
        PagePart find(PagePart part) {
            for (PagePart p : this) {
                if (p.equals(part)) {
                    return p;
                }
            }
            return null;
        }

        void recycle() {
            for (PagePart p : this) {
                if (!p.getRenderedBitmap().isRecycled()) {
                    p.getRenderedBitmap().recycle();
                }
            }

            clear();
        }
    }

    class PagePartComparator implements Comparator<PagePart> {
        @Override
        public int compare(PagePart part1, PagePart part2) {
            if (part1.getCacheOrder() == part2.getCacheOrder()) {
                return 0;
            }
            return part1.getCacheOrder() > part2.getCacheOrder() ? 1 : -1;
        }
    }

}
