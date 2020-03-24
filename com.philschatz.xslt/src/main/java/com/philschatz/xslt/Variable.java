package com.philschatz.xslt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.microsoft.java.debug.core.protocol.Types;
import com.philschatz.xslt.ObjectPool.Unit;

import net.sf.saxon.om.AxisInfo;
import net.sf.saxon.om.GroundedValue;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.om.SequenceIterator;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.tree.iter.AxisIterator;
import net.sf.saxon.type.Type;
import net.sf.saxon.value.StringValue;

public class Variable {
  private final long id;
  private final String k;
  private final GroundedValue v;
  private final ObjectPool<ObjectPool.Unit, Variable> pool;
  Variable(String k, GroundedValue v, ObjectPool<ObjectPool.Unit, Variable> pool) { 
    this.k = k;
    this.v = v;
    this.pool = pool;
    this.id = pool.store(ObjectPool.UNIT, this);
  }

  public String getKey() { return k; }
  public String getValue() { return getValue(this.v); }
  public String getType() { return getType(this.v);  }
  
  public Types.Variable toResponse() {
    int childPtr =_getChilds().size() > 0 ? (int) id : 0;
    return new Types.Variable(getKey(), getValue(), getType(), childPtr, "HELP_EVALUATENAME");
  }

  public List<Variable> getChildren() {
    List<Variable> ret = new ArrayList<>();
    for (Map.Entry<String, GroundedValue> entry : _getChilds().entrySet()) {
      Variable child = new Variable(entry.getKey(), entry.getValue(), pool);
      this.pool.store(ObjectPool.UNIT, child);
      ret.add(child);
    }
    return ret;
  }

  private Map<String, GroundedValue> _getChilds() {
    Map<String, GroundedValue> ret = new LinkedHashMap<>();
    if (v == null) {
    } else if (v instanceof NodeInfo) {
      NodeInfo n = (NodeInfo) v;
      if (n.hasChildNodes()) {
        AxisIterator it = n.iterateAxis(AxisInfo.CHILD);
        NodeInfo child;
        int i = 0;
        while((child = it.next()) != null) {
          switch (child.getNodeKind()) {
            case Type.WHITESPACE_TEXT:
            case Type.TEXT:
              // Skip empty whitespace nodes
              if (child.getStringValue().trim().length() == 0) {
                continue;
              }
            case Type.ATTRIBUTE:
            case Type.ELEMENT:
            case Type.PROCESSING_INSTRUCTION:
            case Type.COMMENT:
            case Type.DOCUMENT:
            case Type.NAMESPACE:
              ret.put(String.valueOf(i), child);
              i++;
              break;
          }
        }
      }
    } else if (v instanceof Sequence) {
      try {
        Sequence s = (Sequence) v;
        SequenceIterator si = s.iterate();
        GroundedValue item;
        int i = 0;
        while ((item = si.next()) != null) {
          ret.put(String.valueOf(i), item);
          i++;
        }
      } catch (XPathException e) {
        
      }
    } else {
      throw new Error("BUG: Unsupported type for determining if there are children");
    }
    return ret;
  }

  public static String shortString(String msg) {
    if (msg.length() < 20) {
      return msg;
    } else {
      int len = msg.length();
      return String.format("%s...%s", msg.substring(0, 8), msg.substring(len - 9, len - 1));
    }
  }

  public static String getValue(GroundedValue v) {
    if (v instanceof NodeInfo) {
      NodeInfo n = (NodeInfo) v;
      // Element Attributes do not have source information so use the parent
      switch (n.getNodeKind()) {
        case Type.DOCUMENT:
        case Type.ELEMENT:
        case Type.PROCESSING_INSTRUCTION:
        case Type.COMMENT:
        case Type.NAMESPACE:
          return String.format("%s @%d:%d", n.toShortString(), n.getLineNumber(), n.getColumnNumber());
        case Type.TEXT:
        case Type.WHITESPACE_TEXT:
          return String.format("\"%s\"", shortString(n.getStringValue().trim().replaceAll("\n", "")));
        case Type.ATTRIBUTE:
        default:
          NodeInfo p = n.getParent();
          return String.format("%s @%d:%d", shortString(n.getStringValue()), p.getLineNumber(), p.getColumnNumber());
      }
    } else if (v == null) {
      return "null";
    } else if (v instanceof StringValue) {
      return v.toShortString();
    }
    try {
      if (v instanceof Sequence) {
        Sequence s = (Sequence) v;
        int i = 0;
        SequenceIterator si = s.iterate();
        while ((si.next()) != null) {
            i++;
        }

        if (i == 0) {
          return "[]";
        } else if (i == 1) {
          return s.head().getStringValue();
        } else {
          return String.format("['%s' ... %d]", getValue(s.head()), i);
        }
      }
      return String.format("%s : %s", v.toShortString(), v.getClass().getSimpleName());
    } catch (XPathException e) {
      return String.format("parse error: %s", e.getMessage());
    }
  }

  public static String getType(GroundedValue v) {
    if (v == null) {
      return "null";
    } else if (v instanceof NodeInfo) {
      NodeInfo n = (NodeInfo) v;
      switch (n.getNodeKind()) {
        case Type.ELEMENT: return "ELEMENT";
        case Type.ATTRIBUTE: return "ATTRIBUTE";
        case Type.TEXT: return "TEXT";
        case Type.WHITESPACE_TEXT: return "WHITESPACE_TEXT";
        case Type.PROCESSING_INSTRUCTION: return "PROCESSING_INSTRUCTION";
        case Type.COMMENT: return "COMMENT";
        case Type.DOCUMENT: return "DOCUMENT";
        case Type.NAMESPACE: return "NAMESPACE";
        default: throw new Error("BUG: Unsupported node type. Add it!");
      }
    }
    return v.getClass().getSimpleName();
  }
  
}