/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.log.es;

import com.automq.elasticstream.client.api.AppendResult;
import com.automq.elasticstream.client.api.Client;
import com.automq.elasticstream.client.api.CreateStreamOptions;
import com.automq.elasticstream.client.api.FetchResult;
import com.automq.elasticstream.client.api.KVClient;
import com.automq.elasticstream.client.api.OpenStreamOptions;
import com.automq.elasticstream.client.api.RecordBatch;
import com.automq.elasticstream.client.api.Stream;
import com.automq.elasticstream.client.api.StreamClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.errors.es.SlowFetchHintException;
import org.apache.kafka.common.utils.ThreadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AlwaysSuccessClient implements Client {
    private static final Logger LOGGER = LoggerFactory.getLogger(AlwaysSuccessClient.class);

    // cause of rust frontend is single thread, so we use thread executor free callback overhead.
    // caution: it should call another stream method in one method callback to avoid deadlock, if these method callback
    // executor is same.
    // TODO: change some api to sync call to avoid deadlock.
    private static final ScheduledExecutorService STREAM_MANAGER_RETRY_SCHEDULER = Executors.newScheduledThreadPool(1,
            ThreadUtils.createThreadFactory("stream-manager-retry-%d", true));
    private static final ExecutorService STREAM_MANAGER_CALLBACK_EXECUTORS = Executors.newFixedThreadPool(1,
            ThreadUtils.createThreadFactory("stream-manager-callback-executor-%d", true));
    private static final ScheduledExecutorService FETCH_RETRY_SCHEDULER = Executors.newScheduledThreadPool(1,
            ThreadUtils.createThreadFactory("fetch-retry-scheduler-%d", true));
    private static final ExecutorService APPEND_CALLBACK_EXECUTORS = Executors.newFixedThreadPool(4,
            ThreadUtils.createThreadFactory("append-callback-scheduler-%d", true));
    private static final ExecutorService FETCH_CALLBACK_EXECUTORS = Executors.newFixedThreadPool(4,
            ThreadUtils.createThreadFactory("fetch-callback-scheduler-%d", true));
    private final StreamClient streamClient;
    private final KVClient kvClient;

    public AlwaysSuccessClient(Client client) {
        this.streamClient = new StreamClientImpl(client.streamClient());
        this.kvClient = client.kvClient();
    }

    @Override
    public StreamClient streamClient() {
        return streamClient;
    }

    @Override
    public KVClient kvClient() {
        return kvClient;
    }

    // TODO: do not retry when stream closed.
    static class StreamClientImpl implements StreamClient {
        private final StreamClient streamClient;

        public StreamClientImpl(StreamClient streamClient) {
            this.streamClient = streamClient;
        }

        @Override
        public CompletableFuture<Stream> createAndOpenStream(CreateStreamOptions options) {
            CompletableFuture<Stream> cf = new CompletableFuture<>();
            createAndOpenStream0(options, cf);
            return cf;
        }

        private void createAndOpenStream0(CreateStreamOptions options, CompletableFuture<Stream> cf) {
            streamClient.createAndOpenStream(options).whenCompleteAsync((stream, ex) -> {
                FutureUtil.suppress(() -> {
                    if (ex != null) {
                        LOGGER.error("Create and open stream fail, retry later", ex);
                        STREAM_MANAGER_RETRY_SCHEDULER.schedule(() -> createAndOpenStream0(options, cf), 3, TimeUnit.SECONDS);
                    } else {
                        cf.complete(new StreamImpl(stream));
                    }
                }, LOGGER);
            }, STREAM_MANAGER_CALLBACK_EXECUTORS);
        }

        @Override
        public CompletableFuture<Stream> openStream(long streamId, OpenStreamOptions options) {
            CompletableFuture<Stream> cf = new CompletableFuture<>();
            openStream0(streamId, options, cf);
            return cf;
        }

        private void openStream0(long streamId, OpenStreamOptions options, CompletableFuture<Stream> cf) {
            streamClient.openStream(streamId, options).whenCompleteAsync((stream, ex) -> {
                FutureUtil.suppress(() -> {
                    if (ex != null) {
                        LOGGER.error("Create open stream[{}] fail, retry later", streamId, ex);
                        STREAM_MANAGER_RETRY_SCHEDULER.schedule(() -> openStream0(streamId, options, cf), 3, TimeUnit.SECONDS);
                    } else {
                        cf.complete(new StreamImpl(stream));
                    }
                }, LOGGER);
            }, APPEND_CALLBACK_EXECUTORS);
        }
    }

    static class StreamImpl implements Stream {
        private final Stream stream;
        private volatile boolean closed = false;
        private final Map<String, Boolean> slowFetchingOffsetMap = new ConcurrentHashMap<>();
        private final long SLOW_FETCH_TIMEOUT_MILLIS = 10;

        public StreamImpl(Stream stream) {
            this.stream = stream;
        }

        @Override
        public long streamId() {
            return stream.streamId();
        }

        @Override
        public long startOffset() {
            return stream.startOffset();
        }

        @Override
        public long nextOffset() {
            return stream.nextOffset();
        }

        @Override
        public CompletableFuture<AppendResult> append(RecordBatch recordBatch) {
            CompletableFuture<AppendResult> cf = new CompletableFuture<>();
            stream.append(recordBatch)
                    .whenComplete((rst, ex) -> FutureUtil.suppress(() -> {
                        if (ex != null) {
                            cf.completeExceptionally(ex);
                        } else {
                            cf.complete(rst);
                        }
                    }, LOGGER));
            return cf;
        }


        @Override
        public CompletableFuture<FetchResult> fetch(long startOffset, long endOffset, int maxBytesHint) {
            String slowFetchKey = startOffset + "-" + endOffset;
            CompletableFuture<FetchResult> cf = new CompletableFuture<>();
            // If it is recorded as slowFetching, then skip timeout check.
            if (slowFetchingOffsetMap.containsKey(slowFetchKey)) {
                fetch0(startOffset, endOffset, maxBytesHint, cf, slowFetchKey);
            } else {
                // Try to have a quick stream. If fetching is timeout, then complete with SlowFetchHintException.
                stream.fetch(startOffset, endOffset, maxBytesHint)
                    .orTimeout(SLOW_FETCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    .whenComplete((rst, ex) -> FutureUtil.suppress(() -> {
                        if (ex != null) {
                            if (closed) {
                                cf.completeExceptionally(new IllegalStateException("stream already closed"));
                            } else if (ex instanceof TimeoutException){
                                LOGGER.info("Fetch stream[{}] [{},{}) timeout for {} ms, retry with slow fetching", streamId(), startOffset, endOffset, SLOW_FETCH_TIMEOUT_MILLIS);
                                cf.completeExceptionally(new SlowFetchHintException("fetch data too slowly, retry with slow fetching"));
                                slowFetchingOffsetMap.put(slowFetchKey, true);
                            } else {
                                cf.completeExceptionally(ex);
                            }
                        } else {
                            slowFetchingOffsetMap.remove(slowFetchKey);
                            cf.complete(rst);
                        }
                    }, LOGGER));
            }
            return cf;
        }

        private void fetch0(long startOffset, long endOffset, int maxBytesHint, CompletableFuture<FetchResult> cf, String slowFetchKey) {
            stream.fetch(startOffset, endOffset, maxBytesHint).whenCompleteAsync((rst, ex) -> {
                FutureUtil.suppress(() -> {
                    if (ex != null) {
                        LOGGER.error("Fetch stream[{}] [{},{}) fail, retry later", streamId(), startOffset, endOffset);
                        if (!closed) {
                            FETCH_RETRY_SCHEDULER.schedule(() -> fetch0(startOffset, endOffset, maxBytesHint, cf, slowFetchKey), 3, TimeUnit.SECONDS);
                        } else {
                            cf.completeExceptionally(new IllegalStateException("stream already closed"));
                        }
                    } else {
                        slowFetchingOffsetMap.remove(slowFetchKey);
                        cf.complete(rst);
                    }
                }, LOGGER);
            }, FETCH_CALLBACK_EXECUTORS);
        }

        @Override
        public CompletableFuture<Void> trim(long newStartOffset) {
            CompletableFuture<Void> cf = new CompletableFuture<>();
            stream.trim(newStartOffset).whenCompleteAsync((rst, ex) -> {
                FutureUtil.suppress(() -> {
                    if (ex != null) {
                        cf.completeExceptionally(ex);
                    } else {
                        cf.complete(rst);
                    }
                }, LOGGER);
            }, APPEND_CALLBACK_EXECUTORS);
            return cf;
        }

        @Override
        public CompletableFuture<Void> close() {
            closed = true;
            CompletableFuture<Void> cf = new CompletableFuture<>();
            stream.close().whenCompleteAsync((rst, ex) -> FutureUtil.suppress(() -> {
                if (ex != null) {
                    cf.completeExceptionally(ex);
                } else {
                    cf.complete(rst);
                }
            }, LOGGER), APPEND_CALLBACK_EXECUTORS);
            return cf;
        }

        @Override
        public CompletableFuture<Void> destroy() {
            // TODO: restore when elastic stream supporting destroy.
            return CompletableFuture.completedFuture(null);
//            CompletableFuture<Void> cf = new CompletableFuture<>();
//            stream.destroy().whenCompleteAsync((rst, ex) -> {
//                FutureUtil.suppress(() -> {
//                    if (ex != null) {
//                        cf.completeExceptionally(ex);
//                    } else {
//                        cf.complete(rst);
//                    }
//                }, LOGGER);
//            }, APPEND_CALLBACK_EXECUTORS);
//            return cf;
        }
    }
}
