package com.philschatz.xslt;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.microsoft.java.debug.core.adapter.AdapterUtils;
import com.microsoft.java.debug.core.adapter.ErrorCode;
import com.microsoft.java.debug.core.protocol.AbstractProtocolServer;
import com.microsoft.java.debug.core.protocol.Events;
import com.microsoft.java.debug.core.protocol.JsonUtils;
import com.microsoft.java.debug.core.protocol.Messages.Response;
import com.microsoft.java.debug.core.protocol.Requests.Arguments;
import com.microsoft.java.debug.core.protocol.Requests.Command;
import com.microsoft.java.debug.core.protocol.Types.SourceBreakpoint;

import net.sf.saxon.om.GroundedValue;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.om.Sequence;
import net.sf.saxon.om.SequenceIterator;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.type.Type;
import net.sf.saxon.value.StringValue;

import com.microsoft.java.debug.core.protocol.Messages;
import com.microsoft.java.debug.core.protocol.Requests;
import com.microsoft.java.debug.core.protocol.Responses;
import com.microsoft.java.debug.core.protocol.Types;

public class ProtocolServer extends AbstractProtocolServer {
  private static final Logger logger = Logger.getLogger("xslt-debug");
  private Map<Command, IDebugRequestHandler> requestHandlers = new HashMap<Command, IDebugRequestHandler>();
  private DebugContext debugContext = new DebugContext(this);

  public ProtocolServer(InputStream input, OutputStream output) {
    super(input, output);

    registerHandler(Command.INITIALIZE, new IDebugRequestHandler() {
      @Override
      public Response handle(Command command, Arguments arguments, Response response, DebugContext context) {
        Types.Capabilities caps = new Types.Capabilities();
        caps.supportsConfigurationDoneRequest = true;
        caps.supportsEvaluateForHovers = true;
        caps.supportsDataBreakpoints = true;
        response.body = caps;
        context.getProtocolServer().sendEvent(new Events.InitializedEvent());
        return response;
      }
    });

    registerHandler(Command.LAUNCH, new IDebugRequestHandler() {
      @Override
      public Response handle(Command command, Arguments arguments, Response response, DebugContext context) {
        Requests.LaunchArguments args = (Requests.LaunchArguments) arguments;
        context.createTransformer(args.classPaths[0], args.classPaths[1], args.classPaths[2]);
        return response; // or null
      }
    });

    registerHandler(Command.SETBREAKPOINTS, new IDebugRequestHandler() {
      @Override
      public Response handle(Command command, Arguments arguments, Response response, DebugContext context) {
        Requests.SetBreakpointArguments args = (Requests.SetBreakpointArguments) arguments;

        List<Types.Breakpoint> res = new ArrayList<>();
        List<XSLTBreakpoint> bs = new ArrayList<>();
        for (final SourceBreakpoint b : args.breakpoints) {
          bs.add(new XSLTBreakpoint(args.source.path, b.line + 1));
          res.add(new Types.Breakpoint(false));
        }
        context.addBreakpoints(bs);

        response.body = new Responses.SetBreakpointsResponseBody(res);
        return response;
      }
    });

    registerHandler(Command.CONFIGURATIONDONE, new IDebugRequestHandler() {
      @Override
      public Response handle(Command command, Arguments arguments, Response response, DebugContext context) {
        context.startRunning();
        return response;
      }
    });

    registerHandler(Command.THREADS, new IDebugRequestHandler() {
      @Override
      public Response handle(Command command, Arguments arguments, Response response, DebugContext context) {
        List<Types.Thread> res = new ArrayList<>();
        res.add(new Types.Thread(1, "lonely"));
        response.body = new Responses.ThreadsResponseBody(res);
        return response;
      }
    });

    registerHandler(Command.STACKTRACE, new IDebugRequestHandler() {
      @Override
      public Response handle(Command command, Arguments arguments, Response response, DebugContext context) {
        ArrayList<Types.StackFrame> stack = new ArrayList<>();
        int id = 0;
        for (StackFrame s : context.getStackFrames()) {
          stack.add(0,
              new Types.StackFrame(id, s.construct.replace("{http://www.w3.org/1999/XSL/Transform}", "xsl:"),
                  new Types.Source(AdapterUtils.convertPath(s.systemId, true, false), 0),
                  AdapterUtils.convertLineNumber(s.lineNumber, true, true), s.columnNumber));
          id++;
        }

        response.body = new Responses.StackTraceResponseBody(stack, stack.size());
        return response;
      }
    });

    final int LOCAL_VARIABLES = 1;
    // final int TUNNELED_VARIABLES = 3;

    registerHandler(Command.SCOPES, new IDebugRequestHandler() {
      @Override
      public Response handle(Command command, Arguments arguments, Response response, DebugContext context) {
        Requests.ScopesArguments args = (Requests.ScopesArguments) arguments;
        List<Types.Scope> scopes = new ArrayList<>();
        int variablesReference = args.frameId;
        scopes.add(new Types.Scope("Local", variablesReference, false));
        // scopes.add(new Types.Scope("Tunneled", TUNNELED_VARIABLES, true));
        response.body = new Responses.ScopesResponseBody(scopes);
        return response;
      }
    });

    registerHandler(Command.VARIABLES, new IDebugRequestHandler() {
      @Override
      public Response handle(Command command, Arguments arguments, Response response, DebugContext context) {
        Requests.VariablesArguments args = (Requests.VariablesArguments) arguments;
        List<Types.Variable> vars = new ArrayList<>();

        List<StackFrame> fs = context.getStackFrames();
        StackFrame s = fs.get(args.variablesReference); // use the frameId (aka variablesReference)

        // Add the context node to the list of variables
        vars.add(new Types.Variable("(this)", getValue(s.node), getType(s.node), 1, null));

        for (Entry<String, GroundedValue> p : s.variables.entrySet()) {
          vars.add(new Types.Variable(p.getKey(), getValue(p.getValue()), getType(p.getValue()), vars.size(), null));
        }
        
        response.body = new Responses.VariablesResponseBody(vars);
        return response;
      }
    });

    registerHandler(Command.CONTINUE, new IDebugRequestHandler() {
      @Override
      public Response handle(Command command, Arguments arguments, Response response, DebugContext context) {
        context.unpause();
        response.body = new Responses.ContinueResponseBody();
        return response;
      }
    });

    registerHandler(Command.DISCONNECT, new IDebugRequestHandler() {
      @Override
      public Response handle(Command command, Arguments arguments, Response response, DebugContext context) {
        context.stop();
        return response;
      }
    });

  }

  @Override
  protected void dispatchRequest(Messages.Request request) {
    Messages.Response response = new Messages.Response();
    response.request_seq = request.seq;
    response.command = request.command;
    response.success = true;

    Command command = Command.parse(request.command);
    Arguments cmdArgs = JsonUtils.fromJson(request.arguments, command.getArgumentType());
    IDebugRequestHandler handler = requestHandlers.get(command);

    if (handler != null) {
      response = handler.handle(command, cmdArgs, response, debugContext);
    } else {
      final String errorMessage = String.format("Unrecognized request: { _request: %s }", request.command);
      logger.log(Level.SEVERE, errorMessage);
      response = AdapterUtils.createAsyncErrorResponse(response, ErrorCode.UNRECOGNIZED_REQUEST_FAILURE, errorMessage)
          .join();
    }
    this.sendResponse(response);
  }

  private void registerHandler(Command command, IDebugRequestHandler handler) {
    if (requestHandlers.containsKey(command)) {
      throw new RuntimeException("BUG: Duplicate handler for command. Only supports one for now");
    }
    requestHandlers.put(command, handler);
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
        case Type.ELEMENT:
          return String.format("%s @%d:%d", n.getDisplayName(), n.getLineNumber(), n.getColumnNumber());
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
