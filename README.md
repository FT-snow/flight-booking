# Northbound — flight booking

A small Java flight-seat booking app with a dark HTML/CSS/JavaScript frontend and MySQL storage. Bookings persist after the server restarts. Flight and theatre bookings use separate tables in the shared `booking_app` database.

## Run it

Use Java 11+ and MySQL. From this folder, start the app; it securely prompts for the MySQL password if `DB_PASSWORD` is not already set:

```bash
./run.sh
```

The app creates the `booking_app` database and its flight tables on first run. Open <http://localhost:8081>.

`Flight.bookSeat` is synchronized, and the database also has a unique key for each flight/seat pair to prevent duplicate bookings.
