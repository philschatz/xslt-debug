package com.philschatz.xslt;

import net.sf.saxon.lib.TraceListener;
import net.sf.saxon.trace.InstructionInfo;
import net.sf.saxon.trace.LocationKind;
import net.sf.saxon.Controller;
import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.om.Item;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.om.StandardNames;
import java.lang.String;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import net.sf.saxon.lib.Logger;

import com.microsoft.java.debug.core.adapter.AdapterUtils;
import com.microsoft.java.debug.core.protocol.Events;
import com.microsoft.java.debug.core.protocol.Types;
import com.microsoft.java.debug.core.protocol.Events.DebugEvent;

/**
 * A Trace listener that pauses when instructions (or XML) match the breakpoints
 * set in a debugger. Also provides a way to see the current state of variables
 */

public class XSLTDebugTraceListener implements TraceListener {

  private final DebugContext context;
  public List<XSLTBreakpoint> breakpoints = new ArrayList<XSLTBreakpoint>();

  private Stack<StackFrame> instructionStack = new Stack<StackFrame>();
  private Stack<Item> nodeStack = new Stack<Item>();

  private Object lock = new Object();
  private boolean paused;

  public XSLTDebugTraceListener(DebugContext context) {
    this.context = context;
    System.out.println("****************************************");
  }

  public void unpause() {
    synchronized (lock) {
      paused = false;
    }
  }

  private void pause() {
    synchronized (lock) {
      paused = true;
    }
  }

  private boolean isPaused() {
    synchronized (lock) {
      return paused;
    }
  }

  private void spinUntilUnpaused() {
    pause();
    while (isPaused()) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  public void addBreakpoints(List<XSLTBreakpoint> b) {
    synchronized (lock) {
      breakpoints.addAll(b);
    }
  }

  public Stack<StackFrame> getStackFrames() {
    return instructionStack;
  }

  public void clear() {
    synchronized (lock) {
      paused = false;
      instructionStack.clear();
      nodeStack.clear();
      breakpoints.clear();
    }
  }

  /**
   * Method called at the start of execution, that is, when the run-time
   * transformation starts
   * 
   * @param c Controller used
   */
  public void open(Controller c) {
  }

  /**
   * Method that implements the output destination for SaxonEE/PE 9.7
   */
  public void setOutputDestination(Logger logger) {
  }

  /**
   * Method called at the end of execution, that is, when the run-time execution
   * ends
   */
  public void close() {
    this.context.getProtocolServer().sendEvent(new Events.TerminatedEvent(false));
  }

  /**
   * Method that is called when an instruction in the stylesheet gets processed.
   * 
   * @param info    Instruction gives information about the instruction being
   *                executed, and about the context in which it is executed. This
   *                object is mutable, so if information from the InstructionInfo
   *                is to be retained, it must be copied.
   * @param context XPath context used
   */
  public void enter(InstructionInfo info, XPathContext context) {
    int lineNumber = info.getLineNumber();
    int columnNumber = info.getColumnNumber();

    // Get the current file URI
    String systemId = info.getSystemId();

    // Normalize the current file URI
    URI systemIdUri;
    try {
      systemIdUri = new URI(systemId);
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
    systemId = systemIdUri.normalize().toString();

    String construct;
    int constructType = info.getConstructType();
    if (constructType < 1024) {
      construct = StandardNames.getClarkName(constructType);
    } else {
      switch (constructType) {
        case LocationKind.LITERAL_RESULT_ELEMENT:
          construct = "LITERAL_RESULT_ELEMENT";
          break;
        case LocationKind.LITERAL_RESULT_ATTRIBUTE:
          construct = "LITERAL_RESULT_ATTRIBUTE";
          break;
        case LocationKind.EXTENSION_INSTRUCTION:
          construct = "EXTENSION_INSTRUCTION";
          break;
        case LocationKind.TEMPLATE:
          construct = "TEMPLATE";
          break;
        case LocationKind.FUNCTION_CALL:
          construct = "FUNCTION_CALL";
          break;
        case LocationKind.XPATH_IN_XSLT:
          construct = "XPATH_IN_XSLT";
          break;
        case LocationKind.LET_EXPRESSION:
          construct = "LET_EXPRESSION";
          break;
        case LocationKind.TRACE_CALL:
          construct = "TRACE_CALL";
          break;
        case LocationKind.SAXON_EVALUATE:
          construct = "SAXON_EVALUATE";
          break;
        case LocationKind.FUNCTION:
          construct = "FUNCTION";
          break;
        case LocationKind.XPATH_EXPRESSION:
          construct = "XPATH_EXPRESSION";
          break;
        default:
          construct = "Other";
      }
    }

    Object i = context.getContextItem();
    NodeInfo node = null;
    if (i instanceof NodeInfo) {
      node = (NodeInfo) i;
    }

    synchronized (lock) {
      instructionStack.push(new StackFrame(systemId, lineNumber, columnNumber, construct, node));
    }

    for (XSLTBreakpoint b : breakpoints) {
      if (b.path.equals(AdapterUtils.convertPath(systemId, true, false))
          && b.line == AdapterUtils.convertLineNumber(lineNumber, false, true)) {
        this.context.getProtocolServer().sendEvent(new Events.StoppedEvent("breakpoint", 1));
        spinUntilUnpaused();
        break;
      }
    }
  }

  /**
   * Method that is called after processing an instruction of the stylesheet, that
   * is, after any child instructions have been processed.
   * 
   * @param instruction gives the same information that was supplied to the enter
   *                    method, though it is not necessarily the same object. Note
   *                    that the line number of the instruction is that of the
   *                    start tag in the source stylesheet, not the line number of
   *                    the end tag.
   */
  public void leave(InstructionInfo instruction) {
    synchronized (lock) {
      instructionStack.pop();
    }
  }

  /**
   * Method that is called by an instruction that changes the current item in the
   * source document: that is, xsl:for-each, xsl:apply-templates,
   * xsl:for-each-group. The method is called after the enter method for the
   * relevant instruction, and is called once for each item processed.
   * 
   * @param currentItem the new current item. Item objects are not mutable; it is
   *                    safe to retain a reference to the Item for later use.
   */
  public void startCurrentItem(Item currentItem) {
    synchronized (lock) {
      nodeStack.push(currentItem);
    }
  }

  /**
   * Method that is called when an instruction has finished processing a new
   * current item and is ready to select a new current item or revert to the
   * previous current item. The method will be called before the leave() method
   * for the instruction that made this item current.
   * 
   * @param currentItem the item that was current, whose processing is now
   *                    complete. This will represent the same underlying item as
   *                    the corresponding startCurrentItem() call, though it will
   *                    not necessarily be the same actual object.
   */
  public void endCurrentItem(Item currentItem) {
    synchronized (lock) {
      nodeStack.pop();
    }
  }
}

class StackFrame {
  public final String systemId;
  public final int lineNumber;
  public final int columnNumber;
  public final String construct;
  public final NodeInfo node;

  StackFrame(String systemId, int lineNumber, int columnNumber, String construct, NodeInfo node) {
    this.systemId = systemId;
    this.lineNumber = lineNumber;
    this.columnNumber = columnNumber;
    this.construct = construct;
    this.node = node;
  }
}