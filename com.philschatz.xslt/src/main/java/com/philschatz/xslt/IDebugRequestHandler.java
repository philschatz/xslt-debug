package com.philschatz.xslt;

import com.microsoft.java.debug.core.protocol.Messages.Response;
import com.microsoft.java.debug.core.protocol.Requests.Arguments;
import com.microsoft.java.debug.core.protocol.Requests.Command;

public interface IDebugRequestHandler {
  Response handle(Command command, Arguments arguments, Response response, DebugContext context);
}