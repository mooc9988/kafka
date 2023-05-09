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

import com.fasterxml.jackson.annotation.JsonProperty;

public class ElasticStreamSegmentMeta {
    @JsonProperty("bo")
    private long baseOffset;
    @JsonProperty("ct")
    private long createTimestamp;
    @JsonProperty("lmt")
    private long lastModifiedTimestamp;
    @JsonProperty("s")
    private String streamSuffix = "";
    @JsonProperty("ls")
    private SliceRange log = new SliceRange();
    @JsonProperty("os")
    private SliceRange offset = new SliceRange();
    @JsonProperty("ts")
    private SliceRange time = new SliceRange();
    @JsonProperty("txs")
    private SliceRange txn = new SliceRange();

    public ElasticStreamSegmentMeta() {
    }

    public long baseOffset() {
        return baseOffset;
    }

    public void baseOffset(long baseOffset) {
        this.baseOffset = baseOffset;
    }

    public String streamSuffix() {
        return streamSuffix;
    }

    public void streamSuffix(String streamSuffix) {
        this.streamSuffix = streamSuffix;
    }

    public long createTimestamp() {
        return createTimestamp;
    }

    public void createTimestamp(long createTimestamp) {
        this.createTimestamp = createTimestamp;
    }

    public long lastModifiedTimestamp() {
        return lastModifiedTimestamp;
    }

    public void lastModifiedTimestamp(long lastModifiedTimestamp) {
        this.lastModifiedTimestamp = lastModifiedTimestamp;
    }

    public SliceRange log() {
        return log;
    }

    public void log(SliceRange log) {
        this.log = log;
    }

    public SliceRange offset() {
        return offset;
    }

    public void offset(SliceRange offset) {
        this.offset = offset;
    }

    public SliceRange time() {
        return time;
    }

    public void time(SliceRange time) {
        this.time = time;
    }

    public SliceRange txn() {
        return txn;
    }

    public void txn(SliceRange txn) {
        this.txn = txn;
    }

    @Override
    public String toString() {
        return "ElasticStreamSliceMeta{" +
                "segmentBaseOffset=" + baseOffset +
                ", createTimestamp=" + createTimestamp +
                ", lastModifiedTimestamp=" + lastModifiedTimestamp +
                ", streamSuffix='" + streamSuffix + '\'' +
                ", log=" + log +
                ", offset=" + offset +
                ", time=" + time +
                ", txn=" + txn +
                '}';
    }

}
