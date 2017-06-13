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
import android.util.Log;

import com.github.barteksc.pdfviewer.model.PagePart;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;

import static com.github.barteksc.pdfviewer.util.Constants.Cache.CACHE_SIZE;
import static com.github.barteksc.pdfviewer.util.Constants.Cache.THUMBNAILS_CACHE_SIZE;

class CacheManager {

    private static final int PASSIVE_CACHE = 1;

    private static final int ACTIVE_CACHE = 2;

    private static final ArrayList<WeakReference<CacheManager>> managers = new ArrayList<>();

    private PagePartCache passiveCache;

    private PagePartCache activeCache;

    private List<PagePart> thumbnails;

    public static CacheManager create() {
        synchronized (CacheManager.managers) {
            // remove null references
            for (Iterator<WeakReference<CacheManager>> iterator = managers.iterator(); iterator.hasNext();) {
                WeakReference<CacheManager> ref = iterator.next();
                if (ref == null) {
                    iterator.remove();
                }
            }

            CacheManager manager = new CacheManager();
            managers.add(new WeakReference<CacheManager>(manager));
            return manager;
        }
    }

    public CacheManager() {
        activeCache = new PagePartCache(CACHE_SIZE);
        passiveCache = new PagePartCache(CACHE_SIZE);
        thumbnails = new ArrayList<>();
    }

    public void cachePart(PagePart part) {

        // If cache too big, remove and recycle
        int totalSize = 0;

        synchronized (CacheManager.managers) {
            for (WeakReference<CacheManager> ref : CacheManager.managers) {
                if (ref.get() != null) {
                    totalSize += ref.get().size();
                }
            }

            if (totalSize >= CACHE_SIZE) {
                int sizeToFree = 10;
                int[] caches = {PASSIVE_CACHE, ACTIVE_CACHE};

                for (int cacheType : caches) {
                    for (WeakReference<CacheManager> ref : managers) {
                        if (ref.get() != null && ref.get() != this) {
                            sizeToFree -= ref.get().free(cacheType, sizeToFree);
                        }

                        if (sizeToFree <= 0) {
                            break;
                        }
                    }

                    if (sizeToFree <= 0) {
                        break;
                    }
                }

                for (int cacheType : caches) {
                    sizeToFree -= this.free(cacheType, sizeToFree);
                    if (sizeToFree > 0) {
                        break;
                    }
                }
            }
        }


        // Then add part
        activeCache.offer(part);
    }

    public void makeANewSet() {
        passiveCache.appendAll(activeCache);
        activeCache.clear();
    }

    public int size() {
        return passiveCache.size() + activeCache.size();
    }

    private int free(int cacheType, int size) {
        PagePartCache cache = cacheType == PASSIVE_CACHE ? passiveCache : activeCache;
        return cache.free(size);
    }

    private boolean recycleThumbnail() {
        synchronized (thumbnails) {
            if (this.thumbnails.size() > 0) {
                this.thumbnails.remove(0).getRenderedBitmap().recycle();
                return true;
            } else {
                return false;
            }
        }
    }

    public void cacheThumbnail(PagePart part) {
        synchronized (thumbnails) {
            int totalSize = 0;
            for (WeakReference<CacheManager> ref : CacheManager.managers) {
                if (ref.get() != null) {
                    totalSize += ref.get().thumbnails.size();
                }
            }

            // If cache too big, remove and recycle
            if (totalSize >= THUMBNAILS_CACHE_SIZE) {
                boolean freed = false;

                for (WeakReference<CacheManager> ref : CacheManager.managers) {
                    CacheManager manager = ref.get();
                    if (manager != null && manager != this && manager.recycleThumbnail()) {
                        freed = true;
                        break;
                    }
                }

                if (!freed && this.thumbnails.size() > 0) {
                    this.recycleThumbnail();
                }
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
                if (!part.getRenderedBitmap().isRecycled()) {
                    part.getRenderedBitmap().recycle();
                }
            }
            thumbnails.clear();
        }
    }


    class PagePartCache extends PriorityBlockingQueue<PagePart> {
        PagePartCache(int cacheSize) {
            super(cacheSize, new PagePartComparator());
        }

        int free(int count) {
            int freed = 0;

            // The parts with lowest cache order number get freed first
            for (int i = 0; i < count; i++) {
                PagePart p = poll();
                if (p == null) {
                    break;
                }

                if (!p.getRenderedBitmap().isRecycled()) {
                    freed++;
                    p.getRenderedBitmap().recycle();
                }
            }

            return freed;
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
