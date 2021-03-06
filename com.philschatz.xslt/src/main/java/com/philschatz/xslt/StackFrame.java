package com.philschatz.xslt;

import java.util.List;
import java.util.Map;

import net.sf.saxon.om.GroundedValue;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.value.StringValue;

public class StackFrame extends Variable {
  public final String systemId;
  public final int lineNumber;
  public final int columnNumber;
  public final String construct;
  public final Map<String, GroundedValue> parameters;
  public final List<Variable> variables;

  StackFrame(ObjectPool<ObjectPool.Unit, Variable> pool, final String systemId, final int lineNumber, final int columnNumber, final String construct,
      final NodeInfo node, final Map<String, GroundedValue> parameters, final List<Variable> variables) {
        super("I_AM_A_SCOPE_NOT_A_VARIABLE", null, pool);
    this.systemId = systemId;
    this.lineNumber = lineNumber;
    this.columnNumber = columnNumber;
    this.construct = construct;
    this.parameters = parameters;
    this.variables = variables;
    // Add the context node to the list of variables
    this.variables.add(0, new Variable("(this)", node, pool));
  }

  @Override
  public List<Variable> getChildren() {
    return variables;
  }

  public static String convert(final Object o) {
    if (o instanceof NodeInfo) {
      final NodeInfo n = (NodeInfo) o;
      return String.format("#L%d:C%d", n.getLineNumber() + 1, n.getColumnNumber());
    } else if (o instanceof String) { 
      return (String) o;
    } else if (o instanceof StringValue) {
      final StringValue s = (StringValue) o;
      return s.toShortString();
    } else if (o instanceof Sequence) {
      Sequence s = (Sequence) o;
      try {
        return s.iterate().materialize().getStringValue();
      } catch (XPathException e) {
        throw new Error(e.getMessage());
      }
    } else if (o == null) {
      return null;
    } else {
      String err = String.format("Unknown type for=%s", o);
      System.out.println(String.format("Unknown type for=%s", o));
      throw new Error(err);
    }
  }

  public static String getType(Object o) {
    if (o instanceof NodeInfo) {
      return "Node";
    } else if (o instanceof String) { 
      return "string";
    } else if (o instanceof StringValue) {
      return "String";
    } else if (o instanceof Sequence) {
      return "Sequence";
    } else if (o == null) {
      return null;
    } else {
      String err = String.format("Unknown type for=%s", o);
      System.out.println(err);
      throw new Error(err);
    }
  }
}