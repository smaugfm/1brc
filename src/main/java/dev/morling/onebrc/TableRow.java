package dev.morling.onebrc;

@SuppressWarnings("preview")
public class TableRow {

  private final String name;
  private double min;
  private double max;
  private double sum;
  private long count;

  public TableRow(String name, double temp) {
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

  public String toString() {
    double mean = round(sum) / count;
    return STR."\{round(min)}/\{round(mean)}/\{round(max)}";
  }

  public String getName() {
    return name;
  }

  private double round(double value) {
    return Math.round(value * 10.0) / 10.0;
  }
}
