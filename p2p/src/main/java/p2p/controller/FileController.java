package controller;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import service.FileSharer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileController {

    private final FileSharer fileSharer;
    private final HttpServer server;
    private final String uploadDir;
    private final ExecutorService executorService;

    public FileController(int port) throws IOException {
        this.fileSharer = new FileSharer();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.uploadDir = System.getProperty("java.io.tmpdir") + File.separator + "peerlink-uploads";
        this.executorService = Executors.newFixedThreadPool(10);

        File uploadDirFile = new File(uploadDir);
        if (!uploadDirFile.exists()) {
            uploadDirFile.mkdirs();
        }

        server.createContext("/upload", new UploadHandler());
        server.createContext("/download", new Multiparser.DownloadHandler());
        server.createContext("/", new CORSHandler());
        server.setExecutor(executorService);
    }

    public void start() {
        server.start();
        System.out.println("API server started on port " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        executorService.shutdown();
        System.out.println("API server stopped");
    }

    // --- Handler to allow cross-origin requests ---
    private static class CORSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1); // No content
                return;
            }

            String response = "NOT FOUND";
            exchange.sendResponseHeaders(404, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    // --- Upload Handler ---
    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                String response = "Method Not Allowed";
                exchange.sendResponseHeaders(405, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                String response = "Bad Request: Content-Type must be multipart/form-data";
                exchange.sendResponseHeaders(400, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            if (!contentType.contains("boundary=")) {
                String response = "Bad Request: Missing boundary in Content-Type";
                exchange.sendResponseHeaders(400, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            try (OutputStream os = exchange.getResponseBody()) {
                String boundary = contentType.split("boundary=")[1];
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                exchange.getRequestBody().transferTo(baos);
                byte[] requestData = baos.toByteArray();

                Multiparser parser = new Multiparser(requestData, boundary);
                Multiparser.ParseResult result = parser.parse();

                if (result == null) {
                    String response = "Bad request: Could not parse file content";
                    exchange.sendResponseHeaders(400, response.length());
                    os.write(response.getBytes());
                    return;
                }

                String fileName = result.fileName;
                if (fileName == null || fileName.trim().isEmpty()) {
                    fileName = "unnamed-file";
                }

                // Generate a unique filename to prevent overwrite
                String uniqueFileName = System.currentTimeMillis() + "-" + fileName;

                File filePath = new File(uploadDir, uniqueFileName);
                try (FileOutputStream fos = new FileOutputStream(filePath)) {
                    fos.write(result.fileContent);
                }
                int port = fileSharer.offerFile(filePath);
                // Start a new thread to serve the file
                new Thread(() -> {
                    try {
                        fileSharer.startFileServer(port);
                    } catch (IOException e) {
                        System.err.println("Failed to start file server on port " + port + ": " + e.getMessage());
                    }
                }).start();

                // Prepare JSON response with the port number
                String jsonResponse = "{\"port\":" + port + "}";

                // Set content type and send response
                headers.add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jsonResponse.getBytes().length);

                try (OutputStream oss = exchange.getResponseBody()) {
                    oss.write(jsonResponse.getBytes());
                }

            } catch (Exception ex) {
                System.err.println("Error processing file upload:" + ex.getMessage());
                String response = "Internal Server Error: " + ex.getMessage();
                exchange.sendResponseHeaders(500, response.length());
                try (OutputStream oos = exchange.getResponseBody()) {
                    oos.write(response.getBytes());
                }
            }
        }
    }

    private static class Multiparser {
        private final byte[] data;
        private final String boundary;

        public Multiparser(byte[] data, String boundary) {
            this.data = data;
            this.boundary = boundary;
        }

        public ParseResult parse() {
            try {
                String dataAsString = new String(data);

                // --- Extract filename ---
                String filenameMarker = "filename=\"";
                int fileNameStart = dataAsString.indexOf(filenameMarker);
                if (fileNameStart == -1) return null;
                fileNameStart += filenameMarker.length();
                int fileNameEnd = dataAsString.indexOf("\"", fileNameStart);
                String fileName = dataAsString.substring(fileNameStart, fileNameEnd);

                // --- Extract Content-Type ---
                String contentType = null;
                String contentTypeMarker = "Content-Type: ";
                int contentTypeStart = dataAsString.indexOf(contentTypeMarker, fileNameEnd);
                if (contentTypeStart != -1) {
                    contentTypeStart += contentTypeMarker.length();
                    int contentTypeEnd = dataAsString.indexOf("\r\n", contentTypeStart);
                    contentType = dataAsString.substring(contentTypeStart, contentTypeEnd);
                    System.out.println("Content-Type: " + contentType);
                }

                String headerEndMarker = "\r\n\r\n";
                int headerEnd = dataAsString.indexOf(headerEndMarker);
                if (headerEnd == -1) return null;

                int contentStart = headerEnd + headerEndMarker.length();
                byte[] boundaryBytes = ("\r\n--" + boundary + "--").getBytes();
                int contentEnd = findSequence(data, boundaryBytes, contentStart);

                if (contentEnd == -1) {
                    boundaryBytes = ("\r\n--" + boundary).getBytes();
                    contentEnd = findSequence(data, boundaryBytes, contentStart);
                }

                if (contentEnd == -1 || contentEnd <= contentStart) return null;

                byte[] fileContent = new byte[contentEnd - contentStart];
                System.arraycopy(data, contentStart, fileContent, 0, fileContent.length);

                return new ParseResult(fileName, fileContent, contentType);

            } catch (Exception ex) {
                System.out.println("Error parsing multipart data" + ex.getMessage());
                return null;
            }
        }

        public static class ParseResult {
            public final String fileName;
            public final byte[] fileContent;
            public final String contentType;

            public ParseResult(String fileName, byte[] fileContent, String contentType) {
                this.fileName = fileName;
                this.fileContent = fileContent;
                this.contentType = contentType;
            }
        }

        private static int findSequence(byte[] data, byte[] sequence, int startPos) {
            outer:
            for (int i = startPos; i <= data.length - sequence.length; i++) {
                for (int j = 0; j < sequence.length; j++) {
                    if (data[i + j] != sequence[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1; // Not found
        }

        // --- Download Handler ---
        private static class DownloadHandler implements HttpHandler {
            @Override
            public void handle(HttpExchange exchange) {
                try {
                    Headers headers = exchange.getResponseHeaders();
                    headers.add("Access-Control-Allow-Origin", "*");

                    if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                        String response = "Method not allowed";
                        exchange.sendResponseHeaders(405, response.getBytes().length);
                        try (OutputStream oos = exchange.getResponseBody()) {
                            oos.write(response.getBytes());
                        }
                        return;
                    }

                    String path = exchange.getRequestURI().getPath();
                    String portStr = path.substring(path.lastIndexOf("/") + 1);  // ✅ Fixed here

                    int port = Integer.parseInt(portStr);
                    try (Socket socket = new Socket("localhost", port)) {
                        InputStream socketInput = socket.getInputStream();
                        File tempFile = File.createTempFile("download-", "tap");
                        String fileName = "downloaded-file";
                        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                            byte[] buffer = new byte[4096];
                            int byteRead;
                            ByteArrayOutputStream headerBaos = new ByteArrayOutputStream();
                            int b;
                            while ((b = socketInput.read()) != -1) {
                                if (b == '\n') break;
                                headerBaos.write(b);
                            }
                            String header = headerBaos.toString().trim();
                            if (header.startsWith("FileName:")) {
                                fileName = header.substring("fileNmme:".length());
                            }
                            while ((byteRead = socketInput.read(buffer)) != -1) {
                                fos.write(buffer, 0, byteRead);
                            }

                            headers.add("Content-Type", "application/octet-stream");
                            headers.add("Content-Disposition", "attachment; filename=\"downloaded-file.txt\"");
                            exchange.sendResponseHeaders(200, tempFile.length());

                            try (OutputStream oss = exchange.getResponseBody();
                                 FileInputStream fis = new FileInputStream(tempFile)) {
                                byte[] buf = new byte[4096];
                                int bytesRead;
                                while ((bytesRead = fis.read(buf)) != -1) {
                                    oss.write(buf, 0, bytesRead);
                                }
                            }
                            tempFile.delete();
                        }
                    } catch (Exception ex) {
                        String response = "Error downloading file: " + ex.getMessage();
                        headers.add("Content-Type", "text/plain");
                        exchange.sendResponseHeaders(400, response.getBytes().length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(response.getBytes());
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Exception in download handler: " + e.getMessage());
                }
            }
        }
    }
}
