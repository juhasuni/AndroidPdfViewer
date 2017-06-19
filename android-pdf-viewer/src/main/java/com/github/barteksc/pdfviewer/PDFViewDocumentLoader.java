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
import android.os.AsyncTask;
import android.util.Log;

import com.github.barteksc.pdfviewer.source.DocumentSource;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

class PDFViewDocumentLoader {

    private static Cache cache = new Cache();
    private static ArrayList<DocumentDecodingTask> executingTasks = new ArrayList();

    public DocumentDecodingTask load(DocumentSource docSource, String password, PDFView pdfView, PdfiumCore pdfiumCore, int firstPageIdx) {
        Context context = pdfView.getContext();
        DocumentDecodingTask decodingTask = null;
        boolean isRunning = false;

        for (DocumentDecodingTask task: executingTasks) {
            if (task.docSource.equals(docSource) && task.context.equals(context)) {
                decodingTask = task;
                isRunning = true;
                break;
            }
        }

        if (decodingTask == null) {
            decodingTask = new DocumentDecodingTask(docSource, password, pdfiumCore, context);
        }

        decodingTask.addView(pdfView, firstPageIdx);

        if (!isRunning) {
            executingTasks.add(decodingTask);
            decodingTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }

        return decodingTask;
    }

    public void unload(DocumentSource docSource, PdfiumCore pdfiumCore) {
        CacheRecord record = cache.release(docSource);

        if (record != null && record.isReleased()) {
            final PdfiumCore core = pdfiumCore;
            final PdfDocument doc = record.pdfDocument;

            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                    core.closeDocument(doc);
                }
            });
        }
    }

    void onTaskExecuted(DocumentDecodingTask task) {
        synchronized (executingTasks) {
            executingTasks.remove(task);
        }
    }

    public class DocumentDecodingTask extends AsyncTask<Void, Void, Throwable> {

        private boolean cancelled;
        private Context context;
        private PdfiumCore pdfiumCore;
        private PdfDocument pdfDocument;
        private String password;
        private DocumentSource docSource;
        private HashMap<PDFView, Integer> views;
        private int pageWidth;
        private int pageHeight;
        private int cancelRequestCount;

        DocumentDecodingTask(DocumentSource docSource, String password, PdfiumCore pdfiumCore, Context context) {
            views = new HashMap<>();
            this.docSource = docSource;
            this.password = password;
            this.pdfiumCore = pdfiumCore;
            this.context = context;
            this.cancelled = false;
            pageWidth = 0;
            pageHeight = 0;
            cancelRequestCount = 0;
        }

        public void addView(PDFView view, int firstPageIdx) {
            views.put(view, firstPageIdx);
        }

        public void requestCancel() {
            cancelRequestCount++;

            if (cancelRequestCount >= views.size()) {
                cancel(true);
            }
        }

        @Override
        protected Throwable doInBackground(Void... params) {
            try {
                CacheRecord record = cache.get(docSource);

                if (record != null) {
                    pdfDocument = record.getPdfDocument();
                }

                if (pdfDocument == null) {
                    pdfDocument = docSource.createDocument(context, pdfiumCore, password);
                    cache.put(docSource, pdfDocument);
                }

                synchronized (views) {
                    for (Map.Entry<PDFView, Integer> entry : views.entrySet()) {
                        pdfiumCore.openPage(pdfDocument, entry.getValue());

                        if (pageWidth == 0 && pageHeight == 0) {
                            pageWidth = pdfiumCore.getPageWidth(pdfDocument, entry.getValue());
                            pageHeight = pdfiumCore.getPageHeight(pdfDocument, entry.getValue());
                        }
                        break;
                    }
                }

                return null;
            } catch (Throwable t) {
                return t;
            }
        }

        @Override
        protected void onPostExecute(Throwable t) {
            onTaskExecuted(this);

            synchronized (views) {
                if (t != null) {
                    for (Map.Entry<PDFView, Integer> entry : views.entrySet()) {
                        entry.getKey().loadError(t);
                    }
                    return;
                }

                if (!cancelled) {
                    for (Map.Entry<PDFView, Integer> entry : views.entrySet()) {
                        cache.retain(docSource);
                        entry.getKey().loadComplete(pdfDocument, pageWidth, pageHeight);
                    }
                }
            }
        }

        @Override
        protected void onCancelled() {
            cancelled = true;
        }
    }

    private static class Cache {
        private ArrayList<CacheRecord> items = new ArrayList();

        void put(DocumentSource docSource, PdfDocument pdfDocument) {
            synchronized (items) {
                CacheRecord record = new CacheRecord(docSource, pdfDocument);
                items.add(record);
            }
        }

        CacheRecord get(DocumentSource docSource) {
            synchronized (items) {
                for (Iterator<CacheRecord> iterator = items.iterator(); iterator.hasNext(); ) {
                    CacheRecord record = iterator.next();
                    DocumentSource source = record.getSource();

                    if (source.equals(docSource)) {
                        return record;
                    }
                }
            }

            return null;
        }

        void retain(DocumentSource docSource) {
            CacheRecord record = get(docSource);

            if (record != null) {
                synchronized (record) {
                    record.retain();
                }
            }

        }

        CacheRecord release(DocumentSource docSource) {
            CacheRecord record = get(docSource);

            if (record != null) {
                synchronized (items) {
                    synchronized (record) {
                        record.release();
                        if (record.isReleased()) {
                            Log.d("PdfLoader", "remove from cache");
                            items.remove(record);
                        }
                        return record;
                    }
                }
            }

            return null;
        }
    }

    private static class CacheRecord {
        DocumentSource source;
        PdfDocument pdfDocument;
        int retainCount;

        CacheRecord(DocumentSource source, PdfDocument pdfDocument) {
            this.source = source;
            this.pdfDocument = pdfDocument;
            retainCount = 0;
            Log.d("PdfLoader", "init");
        }

        boolean isReleased() {
            return retainCount <= 0;
        }

        void retain() {
            retainCount++;
            Log.d("PdfLoader", "after retain" + retainCount);
        }

        void release() {
            retainCount--;
            Log.d("PdfLoader", "after release" + retainCount);
        }

        DocumentSource getSource() {
            return this.source;
        }

        PdfDocument getPdfDocument() {
            return this.pdfDocument;
        }
    }
}
