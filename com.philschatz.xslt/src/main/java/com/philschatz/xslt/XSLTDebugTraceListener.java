package com.philschatz.xslt;

import net.sf.saxon.lib.TraceListener;
import net.sf.saxon.trace.InstructionInfo;
import net.sf.saxon.trace.LocationKind;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.Controller;
import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.om.GroundedValue;
import net.sf.saxon.om.Item;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.om.StandardNames;
import net.sf.saxon.om.StructuredQName;

import java.lang.String;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  private final Stack<StackFrame> instructionStack = new Stack<StackFrame>();
  private final Stack<Item> nodeStack = new Stack<Item>();

  private final ObjectPool<Long, StackFrame> stackframePool = new ObjectPool<>();
  public final ObjectPool<ObjectPool.Unit, Variable> variablesPool = new ObjectPool<>();

  private final Object lock = new Object();
  private boolean paused;

  public XSLTDebugTraceListener(final DebugContext context) {
    this.context = context;
    System.out.println("****************************************");
  }

  public void unpause() {
    synchronized (lock) {
      paused = false;
      variablesPool.clear();
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
      } catch (final InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  public void addBreakpoints(final List<XSLTBreakpoint> b) {
    synchronized (lock) {
      breakpoints.addAll(b);
    }
  }

  public Stack<StackFrame> getStackFrames() {
    synchronized (lock) {
      return instructionStack;
    }
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
  public void open(final Controller c) {
  }

  /**
   * Method that implements the output destination for SaxonEE/PE 9.7
   */
  public void setOutputDestination(final Logger logger) {
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
  public void enter(final InstructionInfo info, final XPathContext context) {
    final int lineNumber = info.getLineNumber();
    final int columnNumber = info.getColumnNumber();
    // System.err.println(String.format("ENTERING %d:%d", lineNumber,
    // columnNumber));

    // Get the current file URI
    String systemId = info.getSystemId();

    // Normalize the current file URI
    URI systemIdUri;
    try {
      systemIdUri = new URI(systemId);
    } catch (final URISyntaxException e) {
      throw new RuntimeException(e);
    }
    systemId = systemIdUri.normalize().toString();

    String construct;
    final int constructType = info.getConstructType();
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

    final Object i = context.getContextItem();
    NodeInfo node = null;
    if (i instanceof NodeInfo) {
      node = (NodeInfo) i;
    }

    // Compute all the variables:
    final Map<String, GroundedValue> parameters = new HashMap<>();
    int p = 0;
    try {
      context.getLocalParameters().materializeValues();
    } catch (final XPathException e) {
      this.context.getProtocolServer().sendEvent(Events.OutputEvent.createStderrOutput(e.getLocalizedMessage()));
    }
    for (final StructuredQName param : context.getLocalParameters().getParameterNames()) {
      try {
        if (param != null) {
          final Sequence v = context.getLocalParameters().getValue(p);
          parameters.put(param.getClarkName(), v.materialize());
        } else {
          parameters.put("NULLISHTHING_atleastone", null);
        }
        p++;
      } catch (XPathException e) {
        e.printStackTrace();
      }
    }

    final List<Variable> variables = new ArrayList<>();
    p = 0;
    for (final Sequence v : context.getStackFrame().getStackFrameValues()) {
      final String name = context.getStackFrame().getStackFrameMap().getVariableMap().get(p).getClarkName();
      try {
        if (v != null) {
          variables.add(new Variable(name, v.iterate().materialize(), variablesPool));
        } else {
          variables.add(new Variable(name, null, variablesPool));
        }
        p++;
      } catch (XPathException e) {
        e.printStackTrace();
      }
    }

    synchronized (lock) {
      instructionStack.push(new StackFrame(variablesPool, systemId, lineNumber, columnNumber, construct, node, parameters, variables));
    }

    for (final XSLTBreakpoint b : breakpoints) {
      if (b.path.equals(AdapterUtils.convertPath(systemId, true, false))
          && b.line == AdapterUtils.convertLineNumber(lineNumber, false, true)) {
        // System.err.println(String.format("PAUSING %d:%d", lineNumber, columnNumber));
        this.context.getProtocolServer().sendEvent(new Events.StoppedEvent("breakpoint", 1));
        spinUntilUnpaused();
        break;
      }
    }

    // System.err.println(String.format("ENTERED %d:%d", lineNumber, columnNumber));
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
  public void leave(final InstructionInfo instruction) {
    // System.err.println(String.format("LEAVING %d:%d",
    // instruction.getLineNumber(), instruction.getColumnNumber()));
    // synchronized (lock) {
    //   StackFrame top = instructionStack.pop();
    //   for (Variable v : top.getChildren()) {
    //     variablesPool.removeAllOwnedBy(v);
    //   }
    // }
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
  public void startCurrentItem(final Item currentItem) {
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
  public void endCurrentItem(final Item currentItem) {
    synchronized (lock) {
      nodeStack.pop();
    }
  }
}
