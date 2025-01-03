/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.demo.backend.datasource;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility to throttle a thread to a given number of executions (records) per second. */
final class Throttler {

  private static final Logger LOGGER = LoggerFactory.getLogger(Throttler.class);
  private long recordsProcessedThisSecond = 0; // 当前秒内处理的记录数
  private long lastLogTimeNanos = 0; // 上次日志记录的时间
  private static final long ONE_SECOND_NANOS = 1_000_000_000L; // 1秒对应的纳秒数

  private long throttleBatchSize;
  private long nanosPerBatch;

  private long endOfNextBatchNanos;
  private int currentBatch;

  Throttler(long maxRecordsPerSecond) {
    setup(maxRecordsPerSecond);
  }

  public void adjustMaxRecordsPerSecond(long maxRecordsPerSecond) {
    setup(maxRecordsPerSecond);
  }

  private synchronized void setup(long maxRecordsPerSecond) {
    Preconditions.checkArgument(
        maxRecordsPerSecond == -1 || maxRecordsPerSecond > 0,
        "maxRecordsPerSecond must be positive or -1 (infinite)");

    if (maxRecordsPerSecond == -1) {
      // unlimited speed
      throttleBatchSize = -1;
      nanosPerBatch = 0;
      endOfNextBatchNanos = System.nanoTime() + nanosPerBatch;
      currentBatch = 0;
      return;
    }

    if (maxRecordsPerSecond >= 10000) {
      // high rates: all throttling in intervals of 2ms
      throttleBatchSize = (int) maxRecordsPerSecond / 500;
      nanosPerBatch = 2_000_000L;
    } else {
      throttleBatchSize = ((int) (maxRecordsPerSecond / 20)) + 1;
      nanosPerBatch = ((int) (1_000_000_000L / maxRecordsPerSecond)) * throttleBatchSize;
    }
    this.endOfNextBatchNanos = System.nanoTime() + nanosPerBatch;
    lastLogTimeNanos = endOfNextBatchNanos;
    this.currentBatch = 0;
  }

  synchronized void throttle() throws InterruptedException {
    if (throttleBatchSize == -1) {
      return;
    }
    if (++currentBatch != throttleBatchSize) {
      return;
    }

    // 每秒钟输出一次日志，记录实际处理速率
    recordsProcessedThisSecond += currentBatch;
    currentBatch = 0;

    final long now = System.nanoTime();
    final int millisRemaining = (int) ((endOfNextBatchNanos - now) / 1_000_000);

    if (now - lastLogTimeNanos >= ONE_SECOND_NANOS) {
      // 每秒钟打印一次日志，判断实际处理速率
      LOGGER.info("当前每秒处理的记录数：{}，设定小批速率：{}", recordsProcessedThisSecond, throttleBatchSize);

      // 重置记录数和上次日志时间
      recordsProcessedThisSecond = 0;
      lastLogTimeNanos = now;
    }

    if (millisRemaining > 0) {
      endOfNextBatchNanos += nanosPerBatch;
      Thread.sleep(millisRemaining);
    } else {
      endOfNextBatchNanos = now + nanosPerBatch;
    }
  }
}
