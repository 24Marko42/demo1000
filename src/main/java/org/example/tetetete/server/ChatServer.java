package org.example.tetetete.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChatServer {
    private static final Logger logger = LoggerFactory.getLogger(ChatServer.class);
    private final int port;
    private final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Map<String, String> users = new ConcurrentHashMap<>(); // Хранение пользователей и их паролей
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public ChatServer(int port) {
        this.port = port;
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            logger.info("Сервер запущен на порту {}", port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ClientHandler(clientSocket, this)).start();
            }
        } catch (IOException e) {
            logger.error("Ошибка запуска сервера: {}", e.getMessage());
        }
    }

    public void broadcast(String message, ClientHandler sender) {
        clients.values().forEach(client -> {
            if (client != sender) {
                client.sendMessage(message);
            }
        });
    }

    public synchronized void addClient(String username, ClientHandler clientHandler) {
        clients.put(username, clientHandler);
        broadcast("Пользователь " + username + " присоединился к чату.", null);
        logger.info("Пользователь {} подключен", username);
        updateUserCount();
    }

    public synchronized void removeClient(String username) {
        clients.remove(username);
        broadcast("Пользователь " + username + " покинул чат.", null);
        logger.info("Пользователь {} отключен", username);
        updateUserCount();
    }

    private void updateUserCount() {
        int userCount = clients.size();
        broadcast("USER_COUNT:" + userCount, null);
    }

    public boolean authenticate(String username, String password) {
        return users.containsKey(username) && users.get(username).equals(password);
    }

    public boolean register(String username, String password) {
        if (users.containsKey(username)) {
            return false;
        }
        users.put(username, password);
        return true;
    }

    public static void main(String[] args) {
        int port = 8080; // Порт сервера
        ChatServer chatServer = new ChatServer(port);
        chatServer.start();

        // Запускаем периодическое обновление количества пользователей
        chatServer.scheduler.scheduleAtFixedRate(chatServer::updateUserCount, 0, 1, TimeUnit.SECONDS);
    }

    public static class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private final ChatServer chatServer;
        private BufferedReader reader;
        private PrintWriter writer;
        private String username;

        public ClientHandler(Socket clientSocket, ChatServer chatServer) {
            this.clientSocket = clientSocket;
            this.chatServer = chatServer;
        }

        @Override
        public void run() {
            try {
                reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                writer = new PrintWriter(clientSocket.getOutputStream(), true);

                // Получаем имя пользователя и пароль от клиента
                username = reader.readLine();
                String password = reader.readLine();

                if (username == null || username.isBlank() || password == null || password.isBlank()) {
                    writer.println("Недопустимое имя пользователя или пароль. Подключение завершено.");
                    closeResources();
                    return;
                }

                if (!chatServer.authenticate(username, password)) {
                    writer.println("Неверное имя пользователя или пароль. Подключение завершено.");
                    closeResources();
                    return;
                }

                synchronized (chatServer) {
                    if (chatServer.clients.containsKey(username)) {
                        writer.println("Имя пользователя уже занято. Подключение завершено.");
                        closeResources();
                        return;
                    }
                    chatServer.addClient(username, this);
                }

                String message;
                while ((message = reader.readLine()) != null) {
                    if (message.equalsIgnoreCase("/exit")) {
                        break;
                    }
                    chatServer.broadcast(username + ": " + message, this);
                }
            } catch (IOException e) {
                logger.error("Ошибка связи с клиентом: {}", e.getMessage());
            } finally {
                chatServer.removeClient(username);
                closeResources();
            }
        }

        public void sendMessage(String message) {
            writer.println(message);
        }

        private void closeResources() {
            try {
                if (reader != null) reader.close();
                if (writer != null) writer.close();
                if (clientSocket != null) clientSocket.close();
            } catch (IOException e) {
                logger.error("Ошибка при закрытии ресурсов: {}", e.getMessage());
            }
        }
    }
}
