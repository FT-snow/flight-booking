import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class FlightBooking {
    private static FlightBookingSystem system = new FlightBookingSystem();

    public static void main(String[] args) throws IOException, SQLException {
        system.initialize();

        HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", FlightBooking::handleRequest);
        server.start();

        System.out.println("Flight booking is running at http://localhost:8081");
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();

            if (path.equals("/") && exchange.getRequestMethod().equals("GET")) {
                send(exchange, 200, "text/html; charset=UTF-8", Files.readString(Path.of("index.html")));
            } else if (path.equals("/api/flights") && exchange.getRequestMethod().equals("GET")) {
                sendFlights(exchange);
            } else if (path.equals("/api/seats") && exchange.getRequestMethod().equals("GET")) {
                sendSeats(exchange);
            } else if (path.equals("/api/book") && exchange.getRequestMethod().equals("POST")) {
                bookSeat(exchange);
            } else {
                sendJson(exchange, 404, "{\"message\":\"Page not found\"}");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            sendJson(exchange, 500, "{\"message\":\"Database error. Check the MySQL settings.\"}");
        }
    }

    private static void sendFlights(HttpExchange exchange) throws IOException, SQLException {
        Flight[] flights = system.getFlights();
        String json = "[";

        for (int i = 0; i < flights.length; i++) {
            Flight flight = flights[i];
            json += "{\"id\":" + flight.getId()
                    + ",\"code\":\"" + flight.getCode()
                    + "\",\"from\":\"" + flight.getFrom()
                    + "\",\"to\":\"" + flight.getTo()
                    + "\",\"time\":\"" + flight.getTime()
                    + "\",\"available\":" + flight.getAvailableSeats()
                    + ",\"total\":" + flight.getTotalSeats() + "}";

            if (i < flights.length - 1) {
                json += ",";
            }
        }

        json += "]";
        sendJson(exchange, 200, json);
    }

    private static void sendSeats(HttpExchange exchange) throws IOException, SQLException {
        Map<String, String> query = readForm(exchange.getRequestURI().getRawQuery());
        Flight flight = system.getFlight(readNumber(query.get("flightId")));

        if (flight == null) {
            sendJson(exchange, 404, "{\"message\":\"Flight not found\"}");
            return;
        }

        sendJson(exchange, 200, flight.getSeatsJson());
    }

    private static void bookSeat(HttpExchange exchange) throws IOException, SQLException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = readForm(body);

        Flight flight = system.getFlight(readNumber(form.get("flightId")));
        int seatNumber = readNumber(form.get("seat"));
        String name = form.get("name");
        String type = form.get("type");

        if (flight == null || name == null || name.trim().isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"Enter your name and choose a flight\"}");
            return;
        }

        Passenger passenger;
        if ("VIP".equals(type)) {
            passenger = new VIPPassenger(name.trim());
        } else {
            passenger = new Passenger(name.trim());
        }

        if (flight.bookSeat(seatNumber, passenger)) {
            sendJson(exchange, 200, "{\"message\":\"Seat " + seatNumber + " is booked. Have a good flight!\"}");
        } else {
            sendJson(exchange, 409, "{\"message\":\"That seat is already taken. Choose another one.\"}");
        }
    }

    private static int readNumber(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return -1;
        }
    }

    private static Map<String, String> readForm(String text) {
        Map<String, String> values = new HashMap<>();
        if (text == null || text.isEmpty()) {
            return values;
        }

        String[] parts = text.split("&");
        for (String part : parts) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2) {
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                values.put(key, value);
            }
        }

        return values;
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        send(exchange, status, "application/json; charset=UTF-8", json);
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

class FlightBookingSystem {
    private Flight[] flights = {
        new Flight(1, "AI 101", "Delhi", "Mumbai", "09:40"),
        new Flight(2, "6E 205", "Mumbai", "Bengaluru", "13:15", 6),
        new Flight(3, "UK 812", "Delhi", "Chennai", "19:25")
    };

    void initialize() throws SQLException {
        try (Connection connection = Database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS flight_schedule ("
                    + "id INT PRIMARY KEY, flight_code VARCHAR(20) NOT NULL, origin VARCHAR(80) NOT NULL, "
                    + "destination VARCHAR(80) NOT NULL, departure_time VARCHAR(20) NOT NULL, total_seats INT NOT NULL)");

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT IGNORE INTO flight_schedule (id, flight_code, origin, destination, departure_time, total_seats) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                for (Flight flight : flights) {
                    insert.setInt(1, flight.getId());
                    insert.setString(2, flight.getCode());
                    insert.setString(3, flight.getFrom());
                    insert.setString(4, flight.getTo());
                    insert.setString(5, flight.getTime());
                    insert.setInt(6, flight.getTotalSeats());
                    insert.executeUpdate();
                }
            }

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS flight_bookings ("
                    + "flight_id INT NOT NULL, seat_number INT NOT NULL, passenger_name VARCHAR(100) NOT NULL, "
                    + "passenger_type VARCHAR(20) NOT NULL, booked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (flight_id, seat_number), "
                    + "FOREIGN KEY (flight_id) REFERENCES flight_schedule(id))");
        }
    }

    Flight[] getFlights() {
        return flights;
    }

    Flight getFlight(int id) {
        for (Flight flight : flights) {
            if (flight.getId() == id) {
                return flight;
            }
        }
        return null;
    }
}

class Flight {
    private int id;
    private String code;
    private String from;
    private String to;
    private String time;
    private int totalSeats;

    Flight(int id, String code, String from, String to, String time) {
        this(id, code, from, to, time, 5);
    }

    Flight(int id, String code, String from, String to, String time, int numberOfSeats) {
        this.id = id;
        this.code = code;
        this.from = from;
        this.to = to;
        this.time = time;
        totalSeats = numberOfSeats;
    }

    int getId() { return id; }
    String getCode() { return code; }
    String getFrom() { return from; }
    String getTo() { return to; }
    String getTime() { return time; }
    int getTotalSeats() { return totalSeats; }

    synchronized boolean bookSeat(int seatNumber, Passenger passenger) throws SQLException {
        if (seatNumber < 1 || seatNumber > totalSeats) {
            return false;
        }

        try (Connection connection = Database.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO flight_bookings (flight_id, seat_number, passenger_name, passenger_type) "
                             + "VALUES (?, ?, ?, ?)")) {
            insert.setInt(1, id);
            insert.setInt(2, seatNumber);
            insert.setString(3, passenger.getName());
            insert.setString(4, passenger.getType());
            insert.executeUpdate();
            System.out.println(passenger.getName() + " booked " + code + " seat " + seatNumber);
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062) {
                return false;
            }
            throw e;
        }
    }

    synchronized int getAvailableSeats() throws SQLException {
        try (Connection connection = Database.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM flight_bookings WHERE flight_id = ?")) {
            query.setInt(1, id);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return totalSeats - result.getInt(1);
            }
        }
    }

    synchronized String getSeatsJson() throws SQLException {
        boolean[] booked = new boolean[totalSeats];

        try (Connection connection = Database.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT seat_number FROM flight_bookings WHERE flight_id = ?")) {
            query.setInt(1, id);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    int seatNumber = result.getInt("seat_number");
                    if (seatNumber >= 1 && seatNumber <= totalSeats) {
                        booked[seatNumber - 1] = true;
                    }
                }
            }
        }

        String json = "{\"seats\":[";

        for (int i = 0; i < totalSeats; i++) {
            json += "{\"number\":" + (i + 1) + ",\"booked\":" + booked[i] + "}";
            if (i < totalSeats - 1) {
                json += ",";
            }
        }

        return json + "]}";
    }
}

class Database {
    static Connection getConnection() throws SQLException {
        String url = System.getenv("DB_URL");
        String user = System.getenv("DB_USER");
        String password = System.getenv("DB_PASSWORD");

        if (url == null || url.isBlank()) {
            url = "jdbc:mysql://localhost:3306/booking_app?createDatabaseIfNotExist=true&serverTimezone=UTC";
        }
        if (user == null || user.isBlank()) {
            user = "root";
        }
        if (password == null) {
            throw new SQLException("Set the DB_PASSWORD environment variable before starting the app.");
        }

        return DriverManager.getConnection(url, user, password);
    }
}

class Passenger {
    private String name;

    Passenger(String name) {
        this.name = name;
    }

    String getName() {
        return name;
    }

    String getType() {
        return "Regular";
    }
}

class VIPPassenger extends Passenger {
    VIPPassenger(String name) {
        super(name);
    }

    @Override
    String getType() {
        return "VIP";
    }
}
