package com.philschatz.xslt;

public class App {
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Exactly 1 commandline argument should be provided, the port number to listen on");
            System.exit(110);
        }
        int port = Integer.parseInt(args[0]);
        XSLTDebugServer server = new XSLTDebugServer(port);
        server.start();
    }
}
