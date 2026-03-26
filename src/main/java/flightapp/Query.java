package flightapp;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs queries against a back-end database
 */
public class Query extends QueryAbstract {
  //
  // Canned queries
  //

  // Example constant and instance variable that uses PreparedStatements.  You
  // DO NOT NEED to use this in your implementation; it's merely an example of
  // how to structure your constants, instance variables, initialization code,
  // utility functions, etc.
  private static final String FLIGHT_CAPACITY_SQL =
    "     SELECT A.num_seats"
    + "     FROM Flights F, N_Numbers N, Aircraft_Types A"
    + "    WHERE F.tail_num = N.n_number"
    + "      AND N.mfr_mdl_code = A.atid"
    + "      AND fid = ?";
  private PreparedStatement flightCapacityStmt;
  // per-terminal session state: who this client is currently logged in as
  private String UserLoggedIn;
  // cache of exactly what the last search printed, so itinerary ids stay stable for book()
  private List<Flight[]> lastSearchResults = new ArrayList<>();

  //
  // Instance variables
  //


  protected Query() throws SQLException, IOException {
    prepareStatements();
  }

  /**
   * Clear the data in any custom tables created.
   * 
   * WARNING! Do not drop any tables and do not clear the flights table.
   */
  public void clearTables() throws SQLException {
    // wipe reservations first so we don't leave dangling references during reset
    final String clearReservationsStmt = "DELETE FROM Reservations_lmswar";
    PreparedStatement psClearReservations = conn.prepareStatement(clearReservationsStmt);
    psClearReservations.executeUpdate();
    psClearReservations.close();

    final String clearUsersStmt = "DELETE FROM Users_lmswar";
    PreparedStatement psClearUsers = conn.prepareStatement(clearUsersStmt);
    psClearUsers.executeUpdate();
    psClearUsers.close();
  }

  /*
   * prepare all the SQL statements in this method.
   */
  private void prepareStatements() throws SQLException {
    // Example initialization of a PreparedStatement. You DO NOT NEED to use
    // this in your implementation; it's merely an example of how to structure
    // your constants, instance variables, initialization code, utility
    // functions, etc.
    flightCapacityStmt = conn.prepareStatement(FLIGHT_CAPACITY_SQL);

    // TODO: YOUR CODE HERE
  }

  /* See QueryAbstract.java for javadoc */
  public String transaction_login(String username, String password) {
    // already logged in on this terminal, don't switch users
    if (UserLoggedIn != null) {
      return "User already logged in\n";
    }

    try {
      final String loginSql = "SELECT pass FROM Users_lmswar WHERE username = ?";
      PreparedStatement loginStmt = conn.prepareStatement(loginSql);
      loginStmt.setString(1, username);
      ResultSet rs = loginStmt.executeQuery();
      if (!rs.next()) {
        // username does not exist
        rs.close();
        loginStmt.close();
        return "Login failed\n";
      }

      byte[] storedPassword = rs.getBytes("pass");
      rs.close();
      loginStmt.close();
      // check typed password against stored salted hash
      if (!PasswordUtils.plaintextMatchesSaltedHash(password, storedPassword)) {
        return "Login failed\n";
      }

      // starting a logged-in session should reset stale pre-login itineraries
      lastSearchResults = new ArrayList<>();
      UserLoggedIn = username;
      return "Logged in as " + username + "\n";
    } catch (SQLException e) {
      return "Login failed\n";
    }
  }

  /* See QueryAbstract.java for javadoc */
  public String transaction_createCustomer(String username, String password, int initAmount) {
    if (initAmount < 0) {
      // balance can't start negative
      return "Failed to create user\n";
    }

    try {
      final String createSql = "INSERT INTO Users_lmswar (username, pass, balance) VALUES (?, ?, ?)";
      PreparedStatement stmt = conn.prepareStatement(createSql);
      stmt.setString(1, username);
      stmt.setBytes(2, PasswordUtils.saltAndHashPassword(password));
      stmt.setInt(3, initAmount);
      stmt.executeUpdate();
      stmt.close();
      return "Created user " + username + "\n";
    } catch (SQLException e) {
      return "Failed to create user\n";
    }
  }

  /* See QueryAbstract.java for javadoc */
  public String transaction_search(String originCity, String destinationCity, 
                                   boolean directFlight, int dayOfMonth,
                                   int numberOfItineraries) {
    // every new search invalidates old itinerary ids
    lastSearchResults = new ArrayList<>();
    // bad itinerary count = no results
    if (numberOfItineraries <= 0) {
      return "No flights match your selection\n";
    }

    try {
      // split by direct vs layover first, then combine
      List<Flight[]> directItineraries = new ArrayList<>();
      List<Flight[]> layoverItineraries = new ArrayList<>();
      List<Flight[]> resultItineraries = new ArrayList<>();

      final String directSql =
        "SELECT F.fid, F.day_of_month, F.cid, F.op_carrier_flight_num, F.origin_city, F.dest_city, "
        + "F.duration_mins, A.num_seats, F.price "
        + "FROM Flights F, N_Numbers N, Aircraft_Types A "
        + "WHERE F.tail_num = N.n_number "
        + "AND N.mfr_mdl_code = A.atid "
        + "AND F.origin_city = ? "
        + "AND F.dest_city = ? "
        + "AND F.day_of_month = ? "
        + "AND F.cancelled = 0 "
        + "ORDER BY F.duration_mins ASC, F.fid ASC "
        + "LIMIT ?";

      PreparedStatement directStmt = conn.prepareStatement(directSql);
      directStmt.setString(1, originCity);
      directStmt.setString(2, destinationCity);
      directStmt.setInt(3, dayOfMonth);
      directStmt.setInt(4, numberOfItineraries);
      ResultSet directRs = directStmt.executeQuery();

      while (directRs.next()) {
        // direct itinerary is represented as [flight, null]
        Flight f = new Flight(
          directRs.getInt("fid"),
          directRs.getInt("day_of_month"),
          directRs.getString("cid"),
          directRs.getString("op_carrier_flight_num"),
          directRs.getString("origin_city"),
          directRs.getString("dest_city"),
          directRs.getInt("duration_mins"),
          directRs.getInt("num_seats"),
          directRs.getInt("price")
        );
        directItineraries.add(new Flight[] {f, null});
      }
      directRs.close();
      directStmt.close();

      // only pull layovers when user allows non-direct search
      if (!directFlight) {
        final String layoverSql =
          "SELECT F1.fid AS fid1, F1.day_of_month AS day1, F1.cid AS cid1, "
          + "F1.op_carrier_flight_num AS flight_num1, F1.origin_city AS origin1, F1.dest_city AS dest1, "
          + "F1.duration_mins AS duration1, A1.num_seats AS capacity1, F1.price AS price1, "
          + "F2.fid AS fid2, F2.day_of_month AS day2, F2.cid AS cid2, "
          + "F2.op_carrier_flight_num AS flight_num2, F2.origin_city AS origin2, F2.dest_city AS dest2, "
          + "F2.duration_mins AS duration2, A2.num_seats AS capacity2, F2.price AS price2 "
          + "FROM Flights F1, Flights F2, N_Numbers N1, Aircraft_Types A1, N_Numbers N2, Aircraft_Types A2 "
          + "WHERE F1.dest_city = F2.origin_city "
          + "AND F1.tail_num = N1.n_number "
          + "AND N1.mfr_mdl_code = A1.atid "
          + "AND F2.tail_num = N2.n_number "
          + "AND N2.mfr_mdl_code = A2.atid "
          + "AND F1.origin_city = ? "
          + "AND F2.dest_city = ? "
          + "AND F1.day_of_month = ? "
          + "AND F2.day_of_month = ? "
          + "AND F1.cancelled = 0 "
          + "AND F2.cancelled = 0 "
          + "ORDER BY (F1.duration_mins + F2.duration_mins) ASC, F1.fid ASC, F2.fid ASC "
          + "LIMIT ?";

        PreparedStatement layoverStmt = conn.prepareStatement(layoverSql);
        layoverStmt.setString(1, originCity);
        layoverStmt.setString(2, destinationCity);
        layoverStmt.setInt(3, dayOfMonth);
        layoverStmt.setInt(4, dayOfMonth);
        layoverStmt.setInt(5, numberOfItineraries);
        ResultSet layoverRs = layoverStmt.executeQuery();

        while (layoverRs.next()) {
          // layover itinerary is represented as [firstLeg, secondLeg]
          Flight F1 = new Flight(
            layoverRs.getInt("fid1"),
            layoverRs.getInt("day1"),
            layoverRs.getString("cid1"),
            layoverRs.getString("flight_num1"),
            layoverRs.getString("origin1"),
            layoverRs.getString("dest1"),
            layoverRs.getInt("duration1"),
            layoverRs.getInt("capacity1"),
            layoverRs.getInt("price1")
          );
          Flight F2 = new Flight(
            layoverRs.getInt("fid2"),
            layoverRs.getInt("day2"),
            layoverRs.getString("cid2"),
            layoverRs.getString("flight_num2"),
            layoverRs.getString("origin2"),
            layoverRs.getString("dest2"),
            layoverRs.getInt("duration2"),
            layoverRs.getInt("capacity2"),
            layoverRs.getInt("price2")
          );
          layoverItineraries.add(new Flight[] {F1, F2});
        }
        layoverRs.close();
        layoverStmt.close();
      }

      // fill from direct first, then use layovers to fill remaining slots
      resultItineraries.addAll(directItineraries);
      int layoverIndex = 0;
      while (resultItineraries.size() < numberOfItineraries && layoverIndex < layoverItineraries.size()) {
        resultItineraries.add(layoverItineraries.get(layoverIndex));
        layoverIndex++;
      }

      if (resultItineraries.isEmpty()) {
        return "No flights match your selection\n";
      }

      // sort exactly by total duration, then by smaller fid(s)
      Collections.sort(resultItineraries, (a, b) -> {
        // primary sort key: total itinerary duration
        int aDuration = a[0].duration + ((a[1] == null) ? 0 : a[1].duration);
        int bDuration = b[0].duration + ((b[1] == null) ? 0 : b[1].duration);
        if (aDuration != bDuration) {
          return Integer.compare(aDuration, bDuration);
        }

        if (a[0].fid != b[0].fid) {
          // first-leg fid tie breaker
          return Integer.compare(a[0].fid, b[0].fid);
        }

        // second-leg fid tie breaker; direct flights use -1 so they compare consistently
        int aFid2 = (a[1] == null) ? -1 : a[1].fid;
        int bFid2 = (b[1] == null) ? -1 : b[1].fid;
        return Integer.compare(aFid2, bFid2);
      });

      // save what we show so book() can use itinerary ids later
      lastSearchResults = new ArrayList<>();
      int outputLimit = Math.min(numberOfItineraries, resultItineraries.size());
      for (int i = 0; i < outputLimit; i++) {
        // store in the exact display order so itinerary i maps to what user saw
        lastSearchResults.add(resultItineraries.get(i));
      }

      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < lastSearchResults.size(); i++) {
        Flight F1 = lastSearchResults.get(i)[0];
        Flight F2 = lastSearchResults.get(i)[1];
        int flightCount = (F2 == null) ? 1 : 2;
        int duration = F1.duration + ((F2 == null) ? 0 : F2.duration);

        sb.append("Itinerary ").append(i).append(": ").append(flightCount).append(" flight(s), ").append(duration).append(" minutes\n");
        sb.append(F1.toString()).append("\n");
        if (F2 != null) {
          sb.append(F2.toString()).append("\n");
        }
      }
      return sb.toString();
    } catch (SQLException e) {
      return "Failed to search\n";
    }
  }

  /* See QueryAbstract.java for javadoc */
  public String transaction_book(int itineraryId) {
    if (UserLoggedIn == null) {
      return "Cannot book reservations, not logged in\n";
    }
    // itinerary ids come from the most recent search shown in this terminal
    if (itineraryId < 0 || itineraryId >= lastSearchResults.size()) {
      return "No such itinerary " + itineraryId + "\n";
    }

    Flight[] itinerary = lastSearchResults.get(itineraryId);
    Flight f1 = itinerary[0];
    Flight f2 = itinerary[1];
    int fid1 = f1.fid;
    Integer fid2 = (f2 == null) ? null : f2.fid;
    int dayOfMonth = f1.dayOfMonth;

    // retry loop handles serializable/deadlock aborts under high contention
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        conn.setAutoCommit(false);

        // first rule check: user can't have 2 reservations on the same day
        final String sameDaySql =
            "SELECT 1 "
          + "FROM Reservations_lmswar R JOIN Flights F ON R.fid1 = F.fid "
          + "WHERE R.username = ? AND F.day_of_month = ? "
          + "LIMIT 1";
        try (PreparedStatement sameDayStmt = conn.prepareStatement(sameDaySql)) {
          sameDayStmt.setString(1, UserLoggedIn);
          sameDayStmt.setInt(2, dayOfMonth);
          try (ResultSet sameDayRs = sameDayStmt.executeQuery()) {
            if (sameDayRs.next()) {
              // user already has a reservation on this day (even if this new one is different)
              conn.rollback();
              return "You cannot book two flights in the same day\n";
            }
          }
        }

        // reservation ids are manual here, so serialize id assignment + insert
        final String lockReservationsSql = "LOCK TABLE Reservations_lmswar IN EXCLUSIVE MODE";
        try (PreparedStatement lockReservationsStmt = conn.prepareStatement(lockReservationsSql)) {
          // this keeps count check + id generation + insert from interleaving across transactions
          lockReservationsStmt.execute();
        }

        final String bookedCountSql = "SELECT COUNT(*) AS cnt FROM Reservations_lmswar WHERE fid1 = ? OR fid2 = ?";
        try (PreparedStatement bookedCountStmt = conn.prepareStatement(bookedCountSql)) {
          // capacity check for leg 1
          bookedCountStmt.setInt(1, fid1);
          bookedCountStmt.setInt(2, fid1);
          try (ResultSet countRs = bookedCountStmt.executeQuery()) {
            countRs.next();
            if (countRs.getInt("cnt") >= getFlightCapacity(fid1)) {
              conn.rollback();
              return "Booking failed\n";
            }
          }

          if (fid2 != null) {
            // if itinerary has a second leg, that leg needs an open seat too
            bookedCountStmt.setInt(1, fid2);
            bookedCountStmt.setInt(2, fid2);
            try (ResultSet countRs = bookedCountStmt.executeQuery()) {
              countRs.next();
              if (countRs.getInt("cnt") >= getFlightCapacity(fid2)) {
                conn.rollback();
                return "Booking failed\n";
              }
            }
          }
        }

        int nextResId;
        // manual id allocation: MAX + 1, protected by the reservations table lock above
        final String nextResIdSql = "SELECT COALESCE(MAX(res_id), 0) + 1 AS next_id FROM Reservations_lmswar";
        try (PreparedStatement nextResIdStmt = conn.prepareStatement(nextResIdSql);
             ResultSet nextResIdRs = nextResIdStmt.executeQuery()) {
          nextResIdRs.next();
          nextResId = nextResIdRs.getInt("next_id");
        }

        final String insertSql = "INSERT INTO Reservations_lmswar (res_id, username, paid, fid1, fid2) VALUES (?, ?, 0, ?, ?)";
        try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
          insertStmt.setInt(1, nextResId);
          insertStmt.setString(2, UserLoggedIn);
          insertStmt.setInt(3, fid1);
          if (fid2 == null) {
            insertStmt.setNull(4, java.sql.Types.INTEGER);
          } else {
            insertStmt.setInt(4, fid2);
          }
          insertStmt.executeUpdate();
        }
        // if we made it here, all checks and insert succeeded together :D
        conn.commit();
        return "Booked flight(s), reservation ID: " + nextResId + "\n";
      } catch (SQLException e) {
        try {
          conn.rollback();
        } catch (SQLException rollbackError) {
          // if rollback itself fails, there's nothing useful left to do here...
        }
        // serialization/deadlock conflict: retry the whole transaction >.<
        if (isRetryable(e)) {
          continue;
        }
        return "Booking failed\n";
      }
    }
    return "Booking failed\n";
  }

  /* See QueryAbstract.java for javadoc */
  public String transaction_pay(int reservationId) {
    if (UserLoggedIn == null) {
      return "Cannot pay, not logged in\n";
    }

    // same retry pattern as book(): conflicts get another shot
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        conn.setAutoCommit(false);

        Integer fid1 = null;
        Integer fid2 = null;
        // lock the reservation so double-pay attempts can't race
        final String reservationSql =
            "SELECT fid1, fid2 "
          + "FROM Reservations_lmswar "
          + "WHERE res_id = ? AND username = ? AND paid = 0 "
          + "FOR UPDATE";
        try (PreparedStatement reservationStmt = conn.prepareStatement(reservationSql)) {
          reservationStmt.setInt(1, reservationId);
          reservationStmt.setString(2, UserLoggedIn);
          try (ResultSet reservationRs = reservationStmt.executeQuery()) {
            if (!reservationRs.next()) {
              // either wrong reservation id, wrong user, or it's already paid
              conn.rollback();
              return "Cannot find unpaid reservation " + reservationId + " under user: "
                   + UserLoggedIn + "\n";
            }
            fid1 = reservationRs.getInt("fid1");
            int tempFid2 = reservationRs.getInt("fid2");
            if (!reservationRs.wasNull()) {
              fid2 = tempFid2;
            }
          }
        }

        int totalCost = 0;
        // compute total itinerary cost from flight prices (1 or 2 legs)
        final String priceSql = "SELECT price FROM Flights WHERE fid = ?";
        try (PreparedStatement priceStmt = conn.prepareStatement(priceSql)) {
          priceStmt.setInt(1, fid1);
          try (ResultSet priceRs = priceStmt.executeQuery()) {
            if (!priceRs.next()) {
              conn.rollback();
              return "Failed to pay for reservation " + reservationId + "\n";
            }
            totalCost += priceRs.getInt("price");
          }

          if (fid2 != null) {
            // add second leg price only for layover itineraries
            priceStmt.setInt(1, fid2);
            try (ResultSet priceRs = priceStmt.executeQuery()) {
              if (!priceRs.next()) {
                conn.rollback();
                return "Failed to pay for reservation " + reservationId + "\n";
              }
              totalCost += priceRs.getInt("price");
            }
          }
        }

        int balance;
        // lock user row before updating balance
        final String balanceSql = "SELECT balance FROM Users_lmswar WHERE username = ? FOR UPDATE";
        try (PreparedStatement balanceStmt = conn.prepareStatement(balanceSql)) {
          balanceStmt.setString(1, UserLoggedIn);
          try (ResultSet balanceRs = balanceStmt.executeQuery()) {
            if (!balanceRs.next()) {
              conn.rollback();
              return "Failed to pay for reservation " + reservationId + "\n";
            }
            balance = balanceRs.getInt("balance");
          }
        }
        // insufficient funds! check before we mutate anything
        if (balance < totalCost) {
          conn.rollback();
          return "User has only " + balance + " in account but itinerary costs " + totalCost + "\n";
        }

        int remaining = balance - totalCost;
        final String updateBalanceSql = "UPDATE Users_lmswar SET balance = ? WHERE username = ?";
        try (PreparedStatement updateBalanceStmt = conn.prepareStatement(updateBalanceSql)) {
          updateBalanceStmt.setInt(1, remaining);
          updateBalanceStmt.setString(2, UserLoggedIn);
          updateBalanceStmt.executeUpdate();
        }

        final String markPaidSql = "UPDATE Reservations_lmswar SET paid = 1 WHERE res_id = ?";
        try (PreparedStatement markPaidStmt = conn.prepareStatement(markPaidSql)) {
          markPaidStmt.setInt(1, reservationId);
          markPaidStmt.executeUpdate();
        }

        // commit balance change + paid flag together (all or nothing)
        conn.commit();
        return "Paid reservation: " + reservationId + " remaining balance: " + remaining + "\n";
      } catch (SQLException e) {
        try {
          conn.rollback();
        } catch (SQLException rollbackError) {
          // same deal as booking: original error is still the one we care about
        }
        // same retry policy as booking
        if (isRetryable(e)) {
          continue;
        }
        return "Failed to pay for reservation " + reservationId + "\n";
      }
    }
    return "Failed to pay for reservation " + reservationId + "\n";
  }

  /* See QueryAbstract.java for javadoc */
  public String transaction_reservations() {
    if (UserLoggedIn == null) {
      return "Cannot view reservations, not logged in\n";
    }

    try {
      // print in reservation id order so output is stable
      final String reservationsSql =
          "SELECT res_id, paid, fid1, fid2 "
        + "FROM Reservations_lmswar "
        + "WHERE username = ? "
        + "ORDER BY res_id ASC";
      try (PreparedStatement reservationsStmt = conn.prepareStatement(reservationsSql)) {
        reservationsStmt.setString(1, UserLoggedIn);
        try (ResultSet rs = reservationsStmt.executeQuery()) {
          StringBuilder sb = new StringBuilder();
          boolean hasReservations = false;

          while (rs.next()) {
            hasReservations = true;
            int reservationId = rs.getInt("res_id");
            boolean paid = rs.getInt("paid") == 1;
            int fid1 = rs.getInt("fid1");
            int fid2 = rs.getInt("fid2");
            boolean hasSecondFlight = !rs.wasNull();

            // output format has to match the project spec exactlyyyyy
            sb.append("Reservation ").append(reservationId).append(" (").append(paid ? "paid" : "unpaid").append("):\n");
            sb.append(fetchFlightById(fid1).toString()).append("\n");
            if (hasSecondFlight) {
              sb.append(fetchFlightById(fid2).toString()).append("\n");
            }
          }

          if (!hasReservations) {
            return "No reservations found\n";
          }
          return sb.toString();
        }
      }
    } catch (SQLException e) {
      return "Failed to retrieve reservations\n";
    }
  }

  /**
   * Example utility function that uses PreparedStatements.  You DO NOT NEED
   * to use this in your implementation; it's merely an example of how to
   * structure your constants, instance variables, initialization code,
   * utility functions, etc.
   */
  private int getFlightCapacity(int fid) throws SQLException {
    flightCapacityStmt.clearParameters();
    flightCapacityStmt.setInt(1, fid);

    ResultSet results = flightCapacityStmt.executeQuery();
    results.next();
    int capacity = results.getInt("num_seats");
    results.close();

    return capacity;
  }

  private Flight fetchFlightById(int fid) throws SQLException {
    final String flightSql =
        "SELECT F.fid, F.day_of_month, F.cid, F.op_carrier_flight_num, F.origin_city, F.dest_city, "
      + "F.duration_mins, A.num_seats, F.price "
      + "FROM Flights F, N_Numbers N, Aircraft_Types A "
      + "WHERE F.tail_num = N.n_number "
      + "AND N.mfr_mdl_code = A.atid "
      + "AND F.fid = ?";
    try (PreparedStatement flightStmt = conn.prepareStatement(flightSql)) {
      flightStmt.setInt(1, fid);
      try (ResultSet flightRs = flightStmt.executeQuery()) {
        if (!flightRs.next()) {
          throw new SQLException("Flight not found: " + fid);
        }
        return new Flight(
          flightRs.getInt("fid"),
          flightRs.getInt("day_of_month"),
          flightRs.getString("cid"),
          flightRs.getString("op_carrier_flight_num"),
          flightRs.getString("origin_city"),
          flightRs.getString("dest_city"),
          flightRs.getInt("duration_mins"),
          flightRs.getInt("num_seats"),
          flightRs.getInt("price")
        );
      }
    }
  }

  /**
   * Utility function to determine whether an error was caused by a retryable
   * error, such as a deadlock.
   */
  private static boolean isRetryable(SQLException e) {
    // FOR ME: 40001: serialization failure, 40P01: deadlock detected
    return "40001".equals(e.getSQLState()) || "40P01".equals(e.getSQLState());
  }

  /**
   * A class to store information about a single flight
   */
  class Flight {
    public int fid;
    public int dayOfMonth;
    public String carrierId;
    public String carrierNum;
    public String originCity;
    public String destCity;
    public int duration;
    public int capacity;
    public int price;

    Flight(int id, int day, String carrier, String cnum, String origin, String dest, int dur,
           int cap, int pri) {
      fid = id;
      dayOfMonth = day;
      carrierId = carrier;
      carrierNum = cnum;
      originCity = origin;
      destCity = dest;
      duration = dur;
      capacity = cap;
      price = pri;
    }
    
    @Override
    public String toString() {
      return "    ID:" + fid + " Day:" + dayOfMonth + " Carrier:" + carrierId
          + " CarrierNum:" + carrierNum + " Origin:'" + originCity + "' Dest:'" + destCity
          + "' Duration:" + duration + " Capacity:" + capacity + " Price:" + price;
    }
  }
}
