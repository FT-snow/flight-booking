# Simple Flight Booking

The Java program serves the flight booking page and handles the booking API. Its worker threads can process requests at the same time, while `Flight.bookSeat` is synchronized to prevent a seat from being booked twice.

Run from this folder with Java 11 or newer:

```bash
export JAVA_HOME="$HOME/.local/opt/jdk-21/usr/lib/jvm/java-21-openjdk-amd64"
export PATH="$JAVA_HOME/bin:$PATH"
javac --add-modules jdk.httpserver FlightBooking.java
java --add-modules jdk.httpserver FlightBooking
```

Open <http://localhost:8081>.
