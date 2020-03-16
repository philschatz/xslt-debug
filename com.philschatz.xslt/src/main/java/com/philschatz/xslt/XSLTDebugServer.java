package com.philschatz.xslt;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

// From https://github.com/microsoft/java-debug
public class XSLTDebugServer {
    private static final Logger logger = Logger.getLogger("xslt-debug");

    private ServerSocket serverSocket = null;
    private boolean isStarted = false;
    private ExecutorService executor = null;

    public XSLTDebugServer(int port) {
        try {
            this.serverSocket = new ServerSocket(port, 1);
            logger.log(Level.INFO, String.format("Started up on port %d", serverSocket.getLocalPort()));
        } catch (IOException e) {
            logger.log(Level.SEVERE, String.format("Failed to create Java Debug Server: %s", e.toString()), e);
        }
    }

    /**
     * Starts the server if it's not started yet.
     */
    public synchronized void start() {
        if (this.serverSocket != null && !this.isStarted) {
            this.isStarted = true;
            this.executor = new ThreadPoolExecutor(0, 100, 30L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
            // Execute eventLoop in a new thread.
            new Thread(new Runnable() {

                @Override
                public void run() {
                    while (true) {
                        try {
                            // Allow server socket to service multiple clients at the same time.
                            // When a request comes in, create a connection thread to process it.
                            // Then the server goes back to listen for new connection request.
                            Socket connection = serverSocket.accept();
                            executor.submit(createConnectionTask(connection));
                        } catch (IOException e) {
                            logger.log(Level.SEVERE,
                                    String.format("Setup socket connection exception: %s", e.toString()), e);
                            closeServerSocket();
                            // If exception occurs when waiting for new client connection, shut down the
                            // connection pool
                            // to make sure no new tasks are accepted. But the previously submitted tasks
                            // will continue to run.
                            shutdownConnectionPool(false);
                            return;
                        }
                    }
                }

            }, "Java Debug Server").start();
        }
    }

    public synchronized void stop() {
        closeServerSocket();
        shutdownConnectionPool(true);
    }

    private synchronized void closeServerSocket() {
        if (serverSocket != null) {
            try {
                logger.info("Close debugserver socket port " + serverSocket.getLocalPort());
                serverSocket.close();
            } catch (IOException e) {
                logger.log(Level.SEVERE, String.format("Close ServerSocket exception: %s", e.toString()), e);
            }
        }
        serverSocket = null;
    }

    private synchronized void shutdownConnectionPool(boolean now) {
        if (this.executor != null) {
            if (now) {
                this.executor.shutdownNow();
            } else {
                this.executor.shutdown();
            }
        }
    }

    private Runnable createConnectionTask(final Socket connection) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    ProtocolServer protocolServer = new ProtocolServer(connection.getInputStream(),
                            connection.getOutputStream());
                    // protocol server will dispatch request and send response in a while-loop.
                    protocolServer.run();
                } catch (IOException e) {
                    logger.log(Level.SEVERE, String.format("Socket connection exception: %s", e.toString()), e);
                } finally {
                    logger.info("Debug connection closed");
                }
            }
        };
    }

}
