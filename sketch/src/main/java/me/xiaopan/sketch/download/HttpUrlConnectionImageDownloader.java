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

package me.xiaopan.sketch.download;

import android.os.Build;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;

import me.xiaopan.sketch.request.DownloadRequest;
import me.xiaopan.sketch.request.DownloadResult;
import me.xiaopan.sketch.Sketch;
import me.xiaopan.sketch.request.BaseRequest;
import me.xiaopan.sketch.cache.DiskCache;
import me.xiaopan.sketch.util.DiskLruCache;
import me.xiaopan.sketch.util.SketchUtils;

/**
 * 使用HttpURLConnection来访问网络的下载器
 */
public class HttpUrlConnectionImageDownloader implements ImageDownloader {
    private static final String NAME = "HttpUrlConnectionImageDownloader";

    private Map<String, ReentrantLock> urlLocks;
    private int maxRetryCount = DEFAULT_MAX_RETRY_COUNT;
    private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int readTimeout = DEFAULT_READ_TIMEOUT;

    public HttpUrlConnectionImageDownloader() {
        this.urlLocks = Collections.synchronizedMap(new WeakHashMap<String, ReentrantLock>());
    }

    @Override
    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    @Override
    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    @Override
    public String getIdentifier() {
        return appendIdentifier(new StringBuilder()).toString();
    }

    @Override
    public StringBuilder appendIdentifier(StringBuilder builder) {
        return builder.append(NAME)
                .append(". ")
                .append("maxRetryCount").append("=").append(maxRetryCount)
                .append(", ")
                .append("connectTimeout").append("=").append(connectTimeout)
                .append(", ")
                .append("readTimeout").append("=").append(readTimeout);
    }

    /**
     * 获取一个URL锁，通过此锁可以防止重复下载
     *
     * @param url 下载地址
     * @return URL锁
     */
    public synchronized ReentrantLock getUrlLock(String url) {
        ReentrantLock urlLock = urlLocks.get(url);
        if (urlLock == null) {
            urlLock = new ReentrantLock();
            urlLocks.put(url, urlLock);
        }
        return urlLock;
    }

    private HttpURLConnection openUrlConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        // HTTP connection reuse which was buggy pre-froyo
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.FROYO) {
            connection.setRequestProperty("http.keepAlive", "false");
        }
        return connection;
    }

    @Override
    public DownloadResult download(DownloadRequest request) {
        // 根据下载地址加锁，防止重复下载
        request.setStatus(BaseRequest.Status.GET_DOWNLOAD_LOCK);
        ReentrantLock urlLock = getUrlLock(request.getAttrs().getRealUri());
        urlLock.lock();

        request.setStatus(BaseRequest.Status.DOWNLOADING);
        DownloadResult result = null;
        int number = 0;
        while (true) {
            // 如果已经取消了就直接结束
            if (request.isCanceled()) {
                if (Sketch.isDebugMode()) {
                    Log.w(Sketch.TAG, SketchUtils.concat(NAME, " - ", "canceled", " - ", "get lock after", " - ", request.getAttrs().getName()));
                }
                break;
            }

            // 如果缓存文件已经存在了就直接返回缓存文件
            if (request.getOptions().isCacheInDisk()) {
                DiskCache.Entry diskCacheEntry = request.getAttrs().getSketch().getConfiguration().getDiskCache().get(request.getAttrs().getDiskCacheKey());
                if (diskCacheEntry != null) {
                    result = new DownloadResult(diskCacheEntry, false);
                    break;
                }
            }

            try {
                result = realDownload(request);
                break;
            } catch (Throwable e) {
                boolean retry = (e instanceof SocketTimeoutException || e instanceof InterruptedIOException) && number < maxRetryCount;
                if (retry) {
                    number++;
                    if (Sketch.isDebugMode()) {
                        Log.w(Sketch.TAG, SketchUtils.concat(NAME, " - ", "download failed", " - ", "retry", " - ", request.getAttrs().getName()));
                    }
                } else {
                    if (Sketch.isDebugMode()) {
                        Log.e(Sketch.TAG, SketchUtils.concat(NAME, " - ", "download failed", " - ", "end", " - ", request.getAttrs().getName()));
                    }
                }
                e.printStackTrace();
                if (!retry) {
                    break;
                }
            }
        }

        // 释放锁
        urlLock.unlock();
        return result;
    }

    private DownloadResult realDownload(DownloadRequest request) throws IOException {
        // 打开连接
        HttpURLConnection connection = openUrlConnection(request.getAttrs().getRealUri());
        connection.connect();

        if (request.isCanceled()) {
            releaseConnection(connection, request);
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, SketchUtils.concat(NAME, " - ", "canceled", " - ", "connect after", " - ", request.getAttrs().getName()));
            }
            return null;
        }

        // 检查状态码
        int responseCode;
        try {
            responseCode = connection.getResponseCode();
        } catch (IOException e) {
            e.printStackTrace();
            releaseConnection(connection, request);
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, SketchUtils.concat(NAME, " - ", "get response code failed", " - ", request.getAttrs().getName(), " - ", "HttpResponseHeader:", getResponseHeadersString(connection)));
            }
            return null;
        }
        String responseMessage;
        try {
            responseMessage = connection.getResponseMessage();
        } catch (IOException e) {
            e.printStackTrace();
            releaseConnection(connection, request);
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, SketchUtils.concat(NAME, " - ", "get response message failed", " - ", request.getAttrs().getName(), " - ", "HttpResponseHeader:", getResponseHeadersString(connection)));
            }
            return null;
        }
        if (responseCode != 200) {
            releaseConnection(connection, request);
            if (Sketch.isDebugMode()) {
                Log.e(Sketch.TAG, SketchUtils.concat(NAME, " - ", "response code exception", " - ", "responseCode:", String.valueOf(responseCode), "; responseMessage:", responseMessage, " - ", request.getAttrs().getName() + " - ", "HttpResponseHeader:", getResponseHeadersString(connection)));
            }
            return null;
        }

        // 检查内容长度
        int contentLength = connection.getHeaderFieldInt("Content-Length", -1);
        if (contentLength <= 0) {
            releaseConnection(connection, request);
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, SketchUtils.concat(NAME, " - ", "content length exception", " - ", "contentLength:" + contentLength, " - ", request.getAttrs().getName(), " - ", "HttpResponseHeader:", getResponseHeadersString(connection)));
            }
            return null;
        }

        return readData(request, connection, contentLength);
    }

    private DownloadResult readData(DownloadRequest request, HttpURLConnection connection, int contentLength) throws IOException {
        // 获取输入流
        InputStream inputStream = connection.getInputStream();

        if (request.isCanceled()) {
            SketchUtils.close(inputStream);
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, SketchUtils.concat(NAME, " - ", "canceled", " - ", "get input stream after", " - ", request.getAttrs().getName()));
            }
            return null;
        }

        // 当不需要将数据缓存到本地的时候就使用ByteArrayOutputStream来存储数据
        DiskLruCache.Editor editor = null;
        if (request.getOptions().isCacheInDisk()) {
            editor = request.getAttrs().getSketch().getConfiguration().getDiskCache().edit(request.getAttrs().getDiskCacheKey());
        }
        OutputStream outputStream;
        if (editor != null) {
            try {
                outputStream = new BufferedOutputStream(editor.newOutputStream(0), BUFFER_SIZE);
            } catch (FileNotFoundException e) {
                SketchUtils.close(inputStream);
                editor.abort();
                throw e;
            }
        } else {
            outputStream = new ByteArrayOutputStream();
        }

        // 读取数据
        int completedLength = 0;
        try {
            completedLength = readData(inputStream, outputStream, request, contentLength);
        } catch (IOException e) {
            if (editor != null) {
                editor.abort();
            }
            throw e;
        } finally {
            SketchUtils.close(outputStream);
            SketchUtils.close(inputStream);
        }

        if (request.isCanceled()) {
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, SketchUtils.concat(NAME, " - ", "canceled", " - ", "read data after", " - ", request.getAttrs().getName()));
            }
            return null;
        }

        if (Sketch.isDebugMode()) {
            Log.i(Sketch.TAG, SketchUtils.concat(NAME, " - ", "download success", " - ", "fileLength:", String.valueOf(completedLength), "/", String.valueOf(contentLength), " - ", request.getAttrs().getName()));
        }

        // 转换结果
        if (request.getOptions().isCacheInDisk() && editor != null) {
            editor.commit();
            return new DownloadResult(request.getAttrs().getSketch().getConfiguration().getDiskCache().get(request.getAttrs().getDiskCacheKey()), true);
        } else if (outputStream instanceof ByteArrayOutputStream) {
            return new DownloadResult(((ByteArrayOutputStream) outputStream).toByteArray());
        } else {
            return null;
        }
    }

    public static void releaseConnection(HttpURLConnection connection, DownloadRequest request) {
        if (connection == null) {
            return;
        }

        InputStream inputStream;
        try {
            inputStream = connection.getInputStream();
        } catch (IOException e) {
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, SketchUtils.concat(NAME, " - ", e.getClass().getName(), " - ", "get input stream failed on release connection", " - ", e.getMessage(), " - ", request.getAttrs().getName()));
            }
            return;
        }
        SketchUtils.close(inputStream);
    }

    public static int readData(InputStream inputStream, OutputStream outputStream, DownloadRequest downloadRequest, int contentLength) throws IOException {
        int realReadCount;
        int completedLength = 0;
        long lastCallbackTime = 0;
        byte[] buffer = new byte[8 * 1024];
        while (true) {
            if(downloadRequest.isCanceled()){
                break;
            }

            realReadCount = inputStream.read(buffer);
            if(realReadCount != -1){
                outputStream.write(buffer, 0, realReadCount);
                completedLength += realReadCount;

                // 每秒钟回调一次进度
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastCallbackTime >= 1000) {
                    lastCallbackTime = currentTime;
                    downloadRequest.updateProgress(contentLength, completedLength);
                }
            }else{
                // 结束的时候再次回调一下进度，确保页面上能显示100%
                downloadRequest.updateProgress(contentLength, completedLength);
                break;
            }
        }
        outputStream.flush();
        return completedLength;
    }

    public static String getResponseHeadersString(HttpURLConnection urlConnection) {
        Map<String, List<String>> headers = urlConnection.getHeaderFields();
        if (headers == null) {
            return null;
        }
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("[");
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (stringBuilder.length() != 1) {
                stringBuilder.append(", ");
            }

            stringBuilder.append("{");

            stringBuilder.append(entry.getKey());

            stringBuilder.append(":");

            List<String> values = entry.getValue();
            if (values.size() == 0) {
                stringBuilder.append("");
            } else if (values.size() == 1) {
                stringBuilder.append(values.get(0));
            } else {
                stringBuilder.append(values.toString());
            }

            stringBuilder.append("}");
        }
        stringBuilder.append("]");
        return stringBuilder.toString();
    }
}
