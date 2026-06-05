package dev.morling.onebrc;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;

@SuppressWarnings("preview")
public class TableRow {

  private final byte[] name;
  private double min;
  private double max;
  private double sum;
  private long count;

  public TableRow(byte[] name, double temp) {
    this.name = name;
    this.min = temp;
    this.max = temp;
    this.sum = temp;
    this.count = 1;
  }

  public void merge(double temp) {
    if (temp < min) {
      min = temp;
    }
    if (temp > max) {
      max = temp;
    }
    sum += temp;
    count++;
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
    double mean = round(sum) / count;
    return STR."\{round(min)}/\{round(mean)}/\{round(max)}";
  }

  public String getName() {return new String(name, UTF_8);}

  private double round(double value) {
    return Math.round(value * 10.0) / 10.0;
  }
}
