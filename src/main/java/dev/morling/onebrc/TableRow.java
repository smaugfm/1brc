package dev.morling.onebrc;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;

@SuppressWarnings("preview")
public class TableRow {

  private final byte[] name;
  private long min;
  private long max;
  private long sum;
  private long count;

  public TableRow(byte[] name, long temp) {
    this.name = name;
    this.min = temp;
    this.max = temp;
    this.sum = temp;
    this.count = 1;
  }

  public void mergeTemp(long temp) {
    if (temp < min) {
      min = temp;
    }
    if (temp > max) {
      max = temp;
    }
    sum += temp;
    count++;
  }

  public void mergeRow(TableRow other) {
    if (other.min < min) {
      min = other.min;
    }
    if (other.max > max) {
      max = other.max;
    }
    sum += other.sum;
    count += other.count;
  }

  /**
   * True if `buf` [position, limit) holds exactly this row's name bytes. No allocation, no position change.
   */
  public boolean nameEquals(ByteBuffer buf) {
    int len = buf.remaining();
    if (len != name.length) {
      return false;        // fast reject — most collisions differ in length
    }
    int base = buf.position();
    for (int i = 0; i < len; i++) {
      if (buf.get(base + i) != name[i]) {
        return false;   // absolute get → doesn't move position
      }
    }
    return true;
  }

  public String toString() {
    double mean = (double) sum / count / 10;
    return STR."\{round((double) min / 10)}/\{round(mean)}/\{round((double) max / 10)}";
  }

  public String getName() {return new String(name, UTF_8);}

  private double round(double value) {
    return Math.round(value * 10.0) / 10.0;
  }
}
