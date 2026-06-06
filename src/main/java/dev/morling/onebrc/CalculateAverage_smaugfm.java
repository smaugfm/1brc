/*
 *  Copyright 2023 The original authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package dev.morling.onebrc;

import static dev.morling.onebrc.Searcher.searchForFirstNewline;
import static dev.morling.onebrc.Searcher.searchForFirstSemicolon;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.TreeMap;
import java.util.zip.CRC32C;

@SuppressWarnings({"Since15", "preview"})
public class CalculateAverage_smaugfm {

  private static final String FILE = "./measurements.txt";

  private static final int CAP = 32768; // must be bigger than 10k
  private static final ThreadLocal<long[]> keysTl = new ThreadLocal<>();
  private static final ThreadLocal<TableRow[]> rowsTl = new ThreadLocal<>();
  private static final ThreadLocal<CRC32C> crcTl = new ThreadLocal<>();

  static void main() throws IOException, InterruptedException {
    try (var channel = FileChannel.open(Paths.get(FILE), StandardOpenOption.READ);
        var arena = Arena.ofShared()) {
      var memSegment = channel.map(MapMode.READ_ONLY, 0, channel.size(), arena);

      Thread[] threads = new Thread[(int) Math.clamp(
          channel.size() / 107,
          1, Runtime.getRuntime().availableProcessors()
      )];
      TableRow[][] results = new TableRow[threads.length][];

      var boundary = 0L;
      for (int i = 0; i < threads.length; i++) {
        long nextBoundary = snapToBoundary(memSegment, channel.size() * (i + 1) / threads.length);
        final int idx = i;
        final long start = boundary;
        threads[i] = new Thread(() -> results[idx] = parseRegion(
            memSegment.asSlice(start, (nextBoundary - start))
        ));
        threads[i].start();
        boundary = nextBoundary;
      }
      for (Thread t : threads) {
        t.join();
      }

      TreeMap<String, TableRow> merged = new TreeMap<>();
      for (TableRow[] table : results) {
        for (TableRow row : table) {
          if (row == null) {
            continue;
          }
          merged.merge(
              row.getName(), row, (a, b) -> {
                a.mergeRow(b);
                return a;
              }
          );
        }
      }

      System.out.println(merged);
    }
  }

  static long snapToBoundary(MemorySegment segment, long candidateBoundary) {
    if (candidateBoundary == segment.byteSize()) {
      return candidateBoundary;
    }

    // max record size: {100_symbols};-99.9\n
    //100+1+5+1 = 107
    var size = segment.byteSize();
    long p = candidateBoundary;
    while (p < size && segment.get(ValueLayout.JAVA_BYTE, p) != '\n') {
      p++;
    }

    return Math.min(p + 1, size);
  }

  enum RegionParseState {
    ACCUMULATING_NAME,
    ACCUMULATING_TEMPERATURE,
  }

  @SuppressWarnings("SameParameterValue")
  static TableRow[] parseRegion(MemorySegment segment) {
    crcTl.set(new CRC32C());
    keysTl.set(new long[CAP]);
    rowsTl.set(new TableRow[CAP]);

    var state = RegionParseState.ACCUMULATING_NAME;
    var offset = 0L;

    // Max record size is 107 bytes: 100 for name, ';', 5 for temp, '\n'

    // ceil(100/8=12.5) = 13
    var name = allocate(13 * 8);
    // ceil(6/8 = 0.75) = 1
    var temp = allocate(8);

    //will be reading in longs
    var word = allocate(8);

    while (offset < segment.byteSize()) {
      if (offset + 8 <= segment.byteSize()) {
        var w = segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
        word.clear().putLong(w);
        offset += 8;
      } else {
        var remaining = (int) (segment.byteSize() - offset);
        word.clear();
        readLessThan8Bytes(segment, offset, remaining, word);
        offset += remaining;
        word.limit(remaining);
      }
      word.rewind();
      switch (state) {
        case ACCUMULATING_NAME: {
          state = accumulateName(word, name, temp);
          break;
        }
        case ACCUMULATING_TEMPERATURE: {
          state = accumulateTemp(word, name, temp);
          break;
        }
      }

      // accumulations must empty the word buffer
      assert !word.hasRemaining();
    }

    return rowsTl.get();
  }

  static RegionParseState accumulateName(
      ByteBuffer word,
      ByteBuffer name,
      ByteBuffer temp
  ) {
    if (!word.hasRemaining()) {
      return RegionParseState.ACCUMULATING_NAME;
    }

    var semicolonIndex = searchForFirstSemicolon(word);

    if (semicolonIndex == -1) {
      name.put(word);
      // no semicolon yet, state stays the same (still accumulating)
      return RegionParseState.ACCUMULATING_NAME;
    } else {
      // found semicolon, saving chunk bytes to name (without semicolon)
      var limit = word.limit();
      //semicolonIndex is relative to the word.position()
      name.put(word.limit(word.position() + (int) semicolonIndex));
      word.limit(limit);

      //skipping semicolon itself
      word.get();

      return accumulateTemp(word, name, temp);
    }
  }

  static RegionParseState accumulateTemp(
      ByteBuffer word,
      ByteBuffer name,
      ByteBuffer temp
  ) {
    if (!word.hasRemaining()) {
      return RegionParseState.ACCUMULATING_TEMPERATURE;
    }

    var newlineIndex = searchForFirstNewline(word);
    if (newlineIndex == -1) {
      temp.put(word);
      return RegionParseState.ACCUMULATING_TEMPERATURE;
    } else {
      // found newline, saving chunk bytes to temp (without newline)
      var limit = word.limit();
      //newlineIndex is relative to the word.position()
      temp.put(word.limit(word.position() + (int) newlineIndex));
      word.limit(limit);

      //skipping newline itself
      word.get();

      //name and temp are found, now we can consume the record in full
      consumeRecord(name, temp);
      name.clear();
      temp.clear();

      // word buffer may still contain unprocessed data from the next record,
      // so we recursively search next
      return accumulateName(word, name, temp);
    }
  }

  static void consumeRecord(ByteBuffer name, ByteBuffer temp) {
    name.flip();
    temp.flip();

    name.mark();
    var crc = crcTl.get();
    crc.reset();
    crc.update(name);
    var hash = crc.getValue();

    name.reset();

    var tempDouble = parseTemp(temp);
    insertOrMergeRow(hash, name, tempDouble);
  }

  static void insertOrMergeRow(long hash, ByteBuffer name, long temp) {
    int idx = (int) (hash & (CAP - 1));
    var keys = keysTl.get();
    var rows = rowsTl.get();
    while (rows[idx] != null) {
      if (keys[idx] == hash && rows[idx].nameEquals(name)) {
        var existing = rows[idx];
        existing.mergeTemp(temp);
        return;
      }
      idx = (idx + 1) & (CAP - 1);
    }
    keys[idx] = hash;
    rows[idx] = new TableRow(toBytes(name), temp);
  }

  static byte[] toBytes(ByteBuffer buf) {
    int len = buf.remaining();
    byte[] b = new byte[len];
    int base = buf.position();
    for (int i = 0; i < len; i++) {
      b[i] = buf.get(base + i);
    }
    return b;
  }

  static long parseTemp(ByteBuffer buffer) {
    int sign = 1;
    byte b = buffer.get();
    if (b == '-') {
      sign = -1;
      b = buffer.get();
    }

    int value = b - '0';
    b = buffer.get();
    if (b != '.') {
      value = value * 10 + (b - '0');
      //skipping the dot
      buffer.position(buffer.position() + 1);
    }
    value = value * 10 + (buffer.get() - '0');

    return sign * value;
  }

  static void readLessThan8Bytes(MemorySegment seg, long offset, int len, ByteBuffer dst) {
    if ((len & 4) != 0) {
      dst.putInt(seg.get(ValueLayout.JAVA_INT_UNALIGNED, offset));
      offset += 4;
    }
    if ((len & 2) != 0) {
      dst.putShort(seg.get(ValueLayout.JAVA_SHORT_UNALIGNED, offset));
      offset += 2;
    }
    if ((len & 1) != 0) {
      dst.put(seg.get(ValueLayout.JAVA_BYTE, offset));
    }
  }

  static ByteBuffer allocate(int size) {
    return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
  }
}

