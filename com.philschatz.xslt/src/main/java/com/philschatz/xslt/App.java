package com.philschatz.xslt;

public class App {
    public static void main(String[] args) {
        XSLTDebugServer server = new XSLTDebugServer(8080);
        server.start();
    }
}
