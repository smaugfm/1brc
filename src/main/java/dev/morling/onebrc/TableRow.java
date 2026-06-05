package dev.morling.onebrc;

@SuppressWarnings("preview")
public class TableRow {

  private String name;
  private double min;
  private double mean;
  private double max;
  private double sum;
  private long count;

  public TableRow(String name, double min, double mean, double max) {
    this.name = name;
    this.min = min;
    this.mean = mean;
    this.max = max;
  }

  public TableRow merge(double temp) {
    if (temp < min) {
      min = temp;
    }
    if (temp > max) {
      max = temp;
    }
    sum += temp;
    count++;

    return this;
  }

  public String toString() {
    return STR."\{round(min)}/\{round(sum / count)}/\{round(max)}";
  }

  public String getName() {
    return name;
  }

  private double round(double value) {
    return Math.round(value * 10.0) / 10.0;
  }
}
