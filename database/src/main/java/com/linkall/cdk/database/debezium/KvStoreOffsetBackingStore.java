// Copyright 2022 Linkall Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.linkall.cdk.database.debezium;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.linkall.cdk.store.KVStore;
import com.linkall.cdk.store.KVStoreFactory;
import io.debezium.embedded.EmbeddedEngine;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.runtime.WorkerConfig;
import org.apache.kafka.connect.storage.Converter;
import org.apache.kafka.connect.storage.MemoryOffsetBackingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class KvStoreOffsetBackingStore extends MemoryOffsetBackingStore {
    public static final String OFFSET_STORAGE_KV_STORE_KEY_CONFIG = "offset.storage.kv.key";
    public static final String OFFSET_CONFIG_VALUE = "offset.config.value";
    private static final String DEFAULT_KEY_NAME = "cdk_debezium_offset";

    private static final Logger logger = LoggerFactory.getLogger(KvStoreOffsetBackingStore.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KVStore store;
    private String keyName;

    public KvStoreOffsetBackingStore() {
        try {
            store = KVStoreFactory.createKVStore();
        } catch (Exception e) {
            throw new RuntimeException("create kv store error", e);
        }
    }

    private static String byteBufferToString(final ByteBuffer byteBuffer) {
        Preconditions.checkNotNull(byteBuffer);
        return new String(byteBuffer.array(), StandardCharsets.UTF_8);
    }

    private static ByteBuffer stringToByteBuffer(final String s) {
        Preconditions.checkNotNull(s);
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void configure(WorkerConfig config) {
        super.configure(config);
        Map<String, String> map = config.originalsStrings();
        keyName =
                Optional.ofNullable(map.get(OFFSET_STORAGE_KV_STORE_KEY_CONFIG)).orElse(DEFAULT_KEY_NAME);
        // read from config
        String offsetConfigValue = map.get(OFFSET_CONFIG_VALUE);
        if (offsetConfigValue == null || offsetConfigValue.isEmpty()) {
            return;
        }
        String engineName = map.get(EmbeddedEngine.ENGINE_NAME.name());
        String dbServerName = map.get("database.server.name");
        Converter keyConverter = new JsonConverter();
        keyConverter.configure(config.originals(), true);
        Map<String, Object> keyMap = new HashMap<>();
        keyMap.put("server", dbServerName);
        byte[] key = keyConverter.fromConnectData(engineName, null, Arrays.asList(engineName, keyMap));
        logger.info("offset config,key: {}, value: {}", new String(key), offsetConfigValue);
        data.put(ByteBuffer.wrap(key), stringToByteBuffer(offsetConfigValue));
    }

    @Override
    public synchronized void start() {
        super.start();
        logger.info("Starting KvStoreOffsetBackingStore with key {}", keyName);
        load();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        logger.info("Stopped KvStoreOffsetBackingStore");
    }

    private void load() {
        byte[] value = store.get(keyName);
        if (value == null) {
            return;
        }
        loadFromKvStore(value);
    }

    @Override
    protected void save() {
        Map<String, String> raw =
                data.entrySet().stream()
                        .collect(
                                Collectors.toMap(
                                        e -> byteBufferToString(e.getKey()), e -> byteBufferToString(e.getValue())));
        try {
            byte[] value = objectMapper.writeValueAsBytes(raw);
            store.set(keyName, value);
        } catch (Exception e) {
            throw new RuntimeException("store offset error", e);
        }
    }

    private void loadFromKvStore(byte[] value) {
        logger.info("Load offset: {}", new String(value));
        Map<String, String> mapAsString;
        try {
            mapAsString = objectMapper.readValue(value, Map.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        data =
                mapAsString.entrySet().stream()
                        .collect(
                                Collectors.toMap(
                                        e -> stringToByteBuffer(e.getKey()), e -> stringToByteBuffer(e.getValue())));
    }
}
