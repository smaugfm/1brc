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
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.TreeMap;
import java.util.zip.CRC32C;

@SuppressWarnings({"Since15", "preview"})
public class CalculateAverage_no_allocations {

  private static final String FILE = "./measurements.txt";

  private static final int CAP = 32768; // must be bigger than 10k
  private static final ThreadLocal<long[]> keysTl = new ThreadLocal<>();
  private static final ThreadLocal<TableRow[]> rowsTl = new ThreadLocal<>();
  private static final ThreadLocal<CRC32C> crcTl = new ThreadLocal<>();

  static void main() throws IOException {
    try (var channel = FileChannel.open(Paths.get(FILE), StandardOpenOption.READ);
        var arena = Arena.ofShared()) {
      var memSegment = channel.map(MapMode.READ_ONLY, 0, channel.size(), arena);
      var rows = parseRegion(memSegment, 0, channel.size());

      var resultsTable = new TreeMap<String, String>();
      for (TableRow row : rows) {
        if (row != null) {
          resultsTable.put(row.getName(), row.toString());
        }
      }

      System.out.println(resultsTable);
    }
  }

  enum RegionParseState {
    ACCUMULATING_NAME,
    ACCUMULATING_TEMPERATURE,
  }

  @SuppressWarnings("SameParameterValue")
  static TableRow[] parseRegion(MemorySegment segment, long startingOffset, long regionSize) {
    crcTl.set(new CRC32C());
    keysTl.set(new long[CAP]);
    rowsTl.set(new TableRow[CAP]);

    var state = RegionParseState.ACCUMULATING_NAME;
    var offset = startingOffset;

    // Max record size is 107 bytes: 100 for name, ';', 5 for temp, '\n'

    // ceil(100/8=12.5) = 13
    var name = ByteBuffer.allocateDirect(13 * 8);
    // ceil(6/8 = 0.75) = 1
    var temp = ByteBuffer.allocateDirect(8);

    //will be reading in longs
    var word = ByteBuffer.allocateDirect(8);

    while (offset < regionSize) {
      if (offset + 8 <= regionSize) {
        var w = segment.get(ValueLayout.JAVA_LONG, offset);
        word.clear().putLong(w);
        offset += 8;
      } else {
        var remaining = (int) (regionSize - offset);
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

  static void insertOrMergeRow(long hash, ByteBuffer name, double temp) {
    int idx = (int) (hash & (CAP - 1));
    var keys = keysTl.get();
    var rows = rowsTl.get();
    while (rows[idx] != null) {
      if (keys[idx] == hash) {
        var existing = rows[idx];
        existing.merge(temp);
        return;
      }
      idx = (idx + 1) & (CAP - 1);
    }
    keys[idx] = hash;
    rows[idx] = new TableRow(UTF_8.decode(name).toString(), temp);
  }

  static double parseTemp(ByteBuffer buffer) {
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

    return sign * value / 10.0;
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
}

