-- Add all your SQL setup statements here. 

-- When we test your submission, you can assume that the following base
-- tables have been created and loaded with data.  Do not alter the
-- following tables' contents or schema in your code.

-- CREATE TABLE Carriers(
--   cid VARCHAR(8) PRIMARY KEY,
--   cname VARCHAR(100)
-- );

-- CREATE TABLE Cancellation_Codes(
--   ccid VARCHAR(1) PRIMARY KEY,
--   description VARCHAR(20)
-- );

-- CREATE TABLE Aircraft_Types (
--   atid VARCHAR(7) PRIMARY KEY,
--   mfr VARCHAR(40),
--   model VARCHAR(30),
--   num_engines INTEGER,
--   num_seats INTEGER NOT NULL,
--   weight_class VARCHAR(7),
--   avg_speed_mph INTEGER
-- );

-- CREATE TABLE N_Numbers (
--   n_number VARCHAR(6) PRIMARY KEY,
--   serial_number VARCHAR(30),
--   mfr_mdl_code VARCHAR(7) REFERENCES aircraft_types(atid) NOT NULL,
--   year_mfr VARCHAR(4),
--   name VARCHAR(50),
--   street VARCHAR(40),
--   street2 VARCHAR(40),
--   city VARCHAR(20),
--   state VARCHAR(2),
--   zip_code VARCHAR(10),
--   region VARCHAR(1),
--   county VARCHAR(3),
--   country VARCHAR(2)
-- );

-- CREATE TABLE Flights (
--   fid INTEGER PRIMARY KEY,
--   year INTEGER,
--   month INTEGER,                  -- 1-12
--   day_of_month INTEGER,           -- 1-31
--   day_of_week INTEGER,            -- 1-7, 1 = Mon
--   cid VARCHAR(8) REFERENCES carriers(cid) NOT NULL,
--   tail_num VARCHAR(6) REFERENCES n_numbers(n_number) NOT NULL,
--   op_carrier_flight_num INTEGER,
--   origin VARCHAR(3),
--   origin_city VARCHAR(40),
--   origin_state VARCHAR(2),
--   dest VARCHAR(3),
--   dest_city VARCHAR(40),
--   dest_state VARCHAR(2),
--   sched_dep_time INTEGER,
--   dep_time INTEGER,
--   dep_delay REAL,
--   sched_arr_time INTEGER,
--   arr_time INTEGER,
--   arr_delay REAL,
--   cancelled INTEGER,
--   cancellation_code VARCHAR(1),
--   duration_mins INTEGER,
--   distance_mi INTEGER,
--   price INTEGER
-- );

CREATE TABLE
    Users_lmswar (
        username VARCHAR(50) PRIMARY KEY,
        pass BYTEA NOT NULL,
        balance INTEGER NOT NULL
    );

CREATE TABLE
    Reservations_lmswar (
        res_id INT PRIMARY KEY,
        username VARCHAR(50) NOT NULL,
        paid INT, -- 0 = not paid, 1 = paid
        fid1 INT NOT NULL,
        fid2 INT, -- NULL = direct, NOT NULL = indirect
        FOREIGN KEY (username) REFERENCES Users_lmswar (username),
        FOREIGN KEY (fid1) REFERENCES Flights (fid),
        FOREIGN KEY (fid2) REFERENCES Flights (fid)
    );
