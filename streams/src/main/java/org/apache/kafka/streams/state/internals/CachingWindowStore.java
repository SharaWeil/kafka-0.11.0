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
package org.apache.kafka.streams.state.internals;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.internals.CacheFlushListener;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.processor.StateStore;
import org.apache.kafka.streams.processor.internals.InternalProcessorContext;
import org.apache.kafka.streams.processor.internals.ProcessorStateManager;
import org.apache.kafka.streams.processor.internals.RecordContext;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.StateSerdes;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;

import java.util.List;

class CachingWindowStore<K, V> extends WrappedStateStore.AbstractStateStore implements WindowStore<K, V>, CachedStateStore<Windowed<K>, V> {


    private final WindowStore<Bytes, byte[]> underlying;
    private final Serde<K> keySerde;
    private final Serde<V> valueSerde;
    private final long windowSize;
    private final SegmentedBytesStore.KeySchema keySchema = new WindowKeySchema();


    private String name;
    private ThreadCache cache;
    private InternalProcessorContext context;
    private StateSerdes<K, V> serdes;
    private CacheFlushListener<Windowed<K>, V> flushListener;
    private final SegmentedCacheFunction cacheFunction;

    CachingWindowStore(final WindowStore<Bytes, byte[]> underlying,
                       final Serde<K> keySerde,
                       final Serde<V> valueSerde,
                       final long windowSize,
                       final long segmentInterval) {
        super(underlying);
        this.underlying = underlying;
        this.keySerde = keySerde;
        this.valueSerde = valueSerde;
        this.windowSize = windowSize;
        this.cacheFunction = new SegmentedCacheFunction(keySchema, segmentInterval);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void init(final ProcessorContext context, final StateStore root) {
        underlying.init(context, root);
        initInternal(context);
        keySchema.init(context.applicationId());
    }

    @SuppressWarnings("unchecked")
    private void initInternal(final ProcessorContext context) {
        this.context = (InternalProcessorContext) context;
        serdes = new StateSerdes<>(ProcessorStateManager.storeChangelogTopic(context.applicationId(), underlying.name()),
                                   keySerde == null ? (Serde<K>) context.keySerde() : keySerde,
                                   valueSerde == null ? (Serde<V>) context.valueSerde() : valueSerde);

        name = context.taskId() + "-" + underlying.name();
        cache = this.context.getCache();

        cache.addDirtyEntryFlushListener(name, new ThreadCache.DirtyEntryFlushListener() {
            @Override
            public void apply(final List<ThreadCache.DirtyEntry> entries) {
                for (ThreadCache.DirtyEntry entry : entries) {
                    final byte[] binaryWindowKey = cacheFunction.key(entry.key()).get();
                    final long timestamp = WindowStoreUtils.timestampFromBinaryKey(binaryWindowKey);

                    final Windowed<K> windowedKey = new Windowed<>(WindowStoreUtils.keyFromBinaryKey(binaryWindowKey, serdes),
                            WindowStoreUtils.timeWindowForSize(timestamp, windowSize));
                    final Bytes key = WindowStoreUtils.bytesKeyFromBinaryKey(binaryWindowKey);
                    maybeForward(entry, key, windowedKey, (InternalProcessorContext) context);
                    underlying.put(key, entry.newValue(), timestamp);
                }
            }
        });
    }

    private void maybeForward(final ThreadCache.DirtyEntry entry,
                              final Bytes key,
                              final Windowed<K> windowedKey,
                              final InternalProcessorContext context) {
        if (flushListener != null) {
            final RecordContext current = context.recordContext();
            context.setRecordContext(entry.recordContext());
            try {
                flushListener.apply(windowedKey,
                                    serdes.valueFrom(entry.newValue()), fetchPrevious(key, windowedKey.window().start()));
            } finally {
                context.setRecordContext(current);
            }
        }
    }

    public void setFlushListener(CacheFlushListener<Windowed<K>, V> flushListener) {
        this.flushListener = flushListener;
    }

    @Override
    public synchronized void flush() {
        cache.flush(name);
        underlying.flush();
    }

    @Override
    public void close() {
        flush();
        cache.close(name);
        underlying.close();
    }

    @Override
    public synchronized void put(final K key, final V value) {
        put(key, value, context.timestamp());
    }

    @Override
    public synchronized void put(final K key, final V value, final long timestamp) {
        // since this function may not access the underlying inner store, we need to validate
        // if store is open outside as well.
        validateStoreOpen();

        final Bytes keyBytes = WindowStoreUtils.toBinaryKey(key, timestamp, 0, serdes);
        final LRUCacheEntry entry = new LRUCacheEntry(serdes.rawValue(value), true, context.offset(),
                                                      context.timestamp(), context.partition(), context.topic());
        cache.put(name, cacheFunction.cacheKey(keyBytes), entry);
    }

    @Override
    public synchronized WindowStoreIterator<V> fetch(final K key, final long timeFrom, final long timeTo) {
        // since this function may not access the underlying inner store, we need to validate
        // if store is open outside as well.
        validateStoreOpen();

        final Bytes keyBytes = Bytes.wrap(serdes.rawKey(key));
        final WindowStoreIterator<byte[]> underlyingIterator = underlying.fetch(keyBytes, timeFrom, timeTo);

        final Bytes cacheKeyFrom = cacheFunction.cacheKey(keySchema.lowerRangeFixedSize(keyBytes, timeFrom));
        final Bytes cacheKeyTo = cacheFunction.cacheKey(keySchema.upperRangeFixedSize(keyBytes, timeTo));
        final ThreadCache.MemoryLRUCacheBytesIterator cacheIterator = cache.range(name, cacheKeyFrom, cacheKeyTo);

        final HasNextCondition hasNextCondition = keySchema.hasNextCondition(keyBytes,
                                                                             keyBytes,
                                                                             timeFrom,
                                                                             timeTo);
        final PeekingKeyValueIterator<Bytes, LRUCacheEntry> filteredCacheIterator = new FilteredCacheIterator(
            cacheIterator, hasNextCondition, cacheFunction
        );

        return new MergedSortedCacheWindowStoreIterator<>(filteredCacheIterator,
                                                          underlyingIterator,
                                                          new StateSerdes<>(serdes.topic(), Serdes.Long(), serdes.valueSerde()));
    }

    @Override
    public KeyValueIterator<Windowed<K>, V> fetch(final K from, final K to, final long timeFrom, final long timeTo) {
        // since this function may not access the underlying inner store, we need to validate
        // if store is open outside as well.
        validateStoreOpen();

        final Bytes keyFromBytes = Bytes.wrap(serdes.rawKey(from));
        final Bytes keyToBytes = Bytes.wrap(serdes.rawKey(to));
        final KeyValueIterator<Windowed<Bytes>, byte[]> underlyingIterator = underlying.fetch(keyFromBytes, keyToBytes, timeFrom, timeTo);

        final Bytes cacheKeyFrom = cacheFunction.cacheKey(keySchema.lowerRange(keyFromBytes, timeFrom));
        final Bytes cacheKeyTo = cacheFunction.cacheKey(keySchema.upperRange(keyToBytes, timeTo));
        final ThreadCache.MemoryLRUCacheBytesIterator cacheIterator = cache.range(name, cacheKeyFrom, cacheKeyTo);

        final HasNextCondition hasNextCondition = keySchema.hasNextCondition(keyFromBytes,
                                                                             keyToBytes,
                                                                             timeFrom,
                                                                             timeTo);
        final PeekingKeyValueIterator<Bytes, LRUCacheEntry> filteredCacheIterator = new FilteredCacheIterator(cacheIterator, hasNextCondition, cacheFunction);

        return new MergedSortedCacheWindowStoreKeyValueIterator<>(
            filteredCacheIterator,
            underlyingIterator,
            serdes,
            windowSize,
            cacheFunction
        );
    }

    private V fetchPrevious(final Bytes key, final long timestamp) {
        try (final WindowStoreIterator<byte[]> iter = underlying.fetch(key, timestamp, timestamp)) {
            if (!iter.hasNext()) {
                return null;
            } else {
                return serdes.valueFrom(iter.next().value);
            }
        }
    }

}
