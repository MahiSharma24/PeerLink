package p2p;

import controller.FileController;

public class App {
    public static void main(String[] args) {
        try {
            FileController fileController = new FileController(8080);
            fileController.start();
            System.out.println("peerLink server started on port 8080");
            System.out.println("UI available at http://localhost:3000");

            // Proper shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down the server");
                fileController.stop();
            }));

            System.out.println("Press Enter to stop the server...");
            System.in.read(); // Wait for Enter key press

            // Optional manual stop after Enter key press
            fileController.stop();

        } catch (Exception ex) {
            System.err.println("Failed to start the server at port 8080");
            ex.printStackTrace();
        }
    }
}
