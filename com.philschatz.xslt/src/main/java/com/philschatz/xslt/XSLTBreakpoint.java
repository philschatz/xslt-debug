package com.philschatz.xslt;

public class XSLTBreakpoint {
  public final String path;
  public final int line;

  public XSLTBreakpoint(String path, int line) {
    this.path = path;
    this.line = line;
  }
}