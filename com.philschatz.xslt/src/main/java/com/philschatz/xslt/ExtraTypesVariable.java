package com.philschatz.xslt;

import com.microsoft.java.debug.core.protocol.Types;

public class ExtraTypesVariable extends Types.Variable {
  /** Optional source for this scope. */
  Types.Source source;
  /** Optional start line of the range covered by this scope. */
	public Integer line;
  /** Optional start column of the range covered by this scope. */
  public Integer column;
  /** Optional end line of the range covered by this scope. */
  // endLine?: number;
  /** Optional end column of the range covered by this scope. */
  // endColumn?: number;
    
  ExtraTypesVariable(String name, String val, String type, int rf, SourceLocation source) {
    super(name, val, type, rf, null);
    if (source != null) {
      this.source = new Types.Source(source.path, 0);
      this.line = source.line;
      this.column = source.column;
    }
  }

  public static class SourceLocation {
    public final String path;
    public final int line;
    public final int column;
    public SourceLocation(String path, int line, int column) {
      this.path = path;
      this.line = line;
      this.column = column;
    }
  }
}
