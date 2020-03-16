package com.philschatz.xslt;

import java.io.File;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.SourceLocator;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamSource;

import com.microsoft.java.debug.core.protocol.Events;
import com.microsoft.java.debug.core.protocol.Types;
import com.microsoft.java.debug.core.protocol.Events.OutputEvent.Category;

import net.sf.saxon.lib.Feature;
import net.sf.saxon.s9api.MessageListener2;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.Xslt30Transformer;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;

public class DebugContext implements Runnable {

  private final XSLTDebugTraceListener listener;
  private final Processor processor;
  private final ProtocolServer server;
  private Xslt30Transformer transformer;
  private StreamSource source;
  private Serializer destination;

  private Thread running;

  public DebugContext(ProtocolServer server) {
    this.listener = new XSLTDebugTraceListener(this);
    this.server = server;
    processor = new Processor(false);
    processor.setConfigurationProperty(Feature.LINE_NUMBERING, true);
    processor.setConfigurationProperty(Feature.TRACE_LISTENER, listener);

    System.setProperty("xspec.coverage.xml", "./xspec-coverage.xml");
    System.setProperty("xspec.xspecfile", "./xspec-filename.xspec");

    System.out.println("Hello World!");
  }

  public ProtocolServer getProtocolServer() {
    return server;
  }

  public void createTransformer(String xsltPath, String sourcePath, String destinationPath) {
    source = new StreamSource(new File(sourcePath));
    destination = processor.newSerializer(new File(destinationPath));

    XsltCompiler c = processor.newXsltCompiler();
    try {
      XsltExecutable ex = c.compile(new StreamSource(new File(xsltPath)));
      transformer = ex.load30();
      transformer.setErrorListener(new ErrorListener() {
        @Override
        public void warning(TransformerException exception) throws TransformerException {
          SourceLocator locator = exception.getLocator();
          Events.OutputEvent evt;
          if (locator != null) {
            evt = Events.OutputEvent.createStderrOutputWithSource(exception.getMessage(),
                new Types.Source(locator.getSystemId(), 0), locator.getLineNumber());
          } else {
            evt = Events.OutputEvent.createStderrOutput(exception.getMessage());
          }
          getProtocolServer().sendEvent(evt);
        }

        @Override
        public void fatalError(TransformerException exception) throws TransformerException {
          SourceLocator locator = exception.getLocator();
          Events.OutputEvent evt;
          if (locator != null) {
            evt = Events.OutputEvent.createStderrOutputWithSource(exception.getMessage(),
                new Types.Source(locator.getSystemId(), 0), locator.getLineNumber());
          } else {
            evt = Events.OutputEvent.createStderrOutput(exception.getMessage());
          }
          getProtocolServer().sendEvent(evt);
        }

        @Override
        public void error(TransformerException exception) throws TransformerException {
          SourceLocator locator = exception.getLocator();
          Events.OutputEvent evt;
          if (locator != null) {
            evt = Events.OutputEvent.createStderrOutputWithSource(exception.getMessage(),
                new Types.Source(locator.getSystemId(), 0), locator.getLineNumber());
          } else {
            evt = Events.OutputEvent.createStderrOutput(exception.getMessage());
          }
          getProtocolServer().sendEvent(evt);
        }
      });
      transformer.setMessageListener(new MessageListener2() {
        @Override
        public void message(XdmNode content, QName errorCode, boolean terminate, SourceLocator locator) {
          // String msg = String.format("%s:(%d:%d) %s", locator.getSystemId(),
          // locator.getLineNumber(), locator.getColumnNumber(),
          // content.getStringValue());
          String msg = content.getStringValue() + '\n';
          getProtocolServer().sendEvent(Events.OutputEvent.createStdoutOutputWithSource(msg,
              new Types.Source(locator.getSystemId(), 0), locator.getLineNumber()));
        }
      });
    } catch (SaxonApiException e) {
      e.printStackTrace();
    }
  }

  public void addBreakpoints(List<XSLTBreakpoint> breakpoints) {
    listener.addBreakpoints(breakpoints);
  }

  public List<StackFrame> getStackFrames() {
    return listener.getStackFrames();
  }

  public void unpause() {
    listener.unpause();
  }

  public void startRunning() {
    if (this.running == null) {
      this.running = new Thread(this, "Xslt Debug Process");
      this.running.start();
    }
  }

  public void stop() {
    this.listener.unpause();
    if (this.running != null) {
      this.running.stop();
    }
  }

  @Override
  public void run() {
    try {
      transformer.transform(source, destination);
    } catch (SaxonApiException e) {
      server.sendEvent(new Events.StoppedEvent(e.getLocalizedMessage(), 1));
    } finally {
      this.running = null;
    }
  }
}