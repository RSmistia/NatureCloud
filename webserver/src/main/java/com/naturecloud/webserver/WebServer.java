package com.naturecloud.webserver;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.naturecloud.dna.DnaHandler;
import com.naturecloud.fractals.FractalsHandler;
import com.naturecloud.grayscott.GrayScottHandler;
import com.naturecloud.javassist.mss.MSS;

public class WebServer {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
        MSS.ensureTablesExist();
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/", new RootHandler());
        server.createContext("/fractals", new FractalsHandler());
        server.createContext("/dna", new DnaHandler());
        server.createContext("/grayscott", new GrayScottHandler());
        server.createContext("/test", new MyHandler());
        server.start();
    }

    static class MyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String response = "This was the query: <" + t.getRequestURI().getQuery() + ">";
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

}
