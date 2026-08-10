/**
 * Copyright (C) 2025 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import liquibase.database.core.HsqlDatabase;
import liquibase.database.jvm.JdbcConnection;

/**
 * The sequence repair, run for real on HSQLDB — the dev rig's own database, and
 * one of the three vendors that draw ids from a sequence rather than an
 * auto-increment counter.
 * <p>
 * Executed rather than mocked because what is being fixed IS the database's
 * behaviour: a test that only checked which strings were sent would have passed
 * just as happily against the broken state it is meant to catch.
 */
public class SequenceRepairChangeTest {

  private static final String SEQUENCE = "SEQ_EMAIL_CONTACT_ADDRESS_ID";

  private static final String TABLE    = "EMAIL_CONTACT_ADDRESS";

  private Connection          connection;

  /**
   * A schema in the state changeset 1.0.0-34 leaves behind: rows seeded with
   * explicit ids, and a sequence still sitting at 1.
   *
   * @throws Exception when the fixture cannot be built
   */
  @BeforeEach
  void setUp() throws Exception {
    connection = DriverManager.getConnection("jdbc:hsqldb:mem:sequenceRepair" + System.nanoTime(), "sa", "");
    try (Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE " + TABLE + " (ID BIGINT PRIMARY KEY, CONTACT_ID BIGINT)");
      statement.execute("CREATE SEQUENCE " + SEQUENCE + " START WITH 1");
      statement.execute("INSERT INTO " + TABLE + " VALUES (1, 1)");
      statement.execute("INSERT INTO " + TABLE + " VALUES (7, 7)");
      statement.execute("INSERT INTO " + TABLE + " VALUES (12, 12)");
    }
  }

  /**
   * Drops the in-memory database.
   *
   * @throws Exception when it cannot be closed
   */
  @AfterEach
  void tearDown() throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SHUTDOWN");
    }
    connection.close();
  }

  /**
   * The whole point: after the repair the sequence hands out an id no seeded row
   * holds, so the first address written after the upgrade can actually be
   * inserted.
   *
   * @throws Exception when the change fails
   */
  @Test
  void theSequenceStopsHandingOutIdsTheSeededRowsAlreadyHold() throws Exception {
    // Without the repair this is 1, which row 1 already has: the insert below
    // fails on the primary key, and so does the contact address repair that runs
    // at the first startup after the upgrade.
    assertEquals(1L, peekNextValue());

    runRepair();

    long next = nextValue();
    assertTrue(next > 12L, "the sequence still hands out a taken id: " + next);
    try (Statement statement = connection.createStatement()) {
      statement.execute("INSERT INTO " + TABLE + " VALUES (" + next + ", 99)");
    }
  }

  /**
   * An empty table has nothing to clear, so the sequence is left where it is
   * rather than pushed forward for no reason.
   *
   * @throws Exception when the change fails
   */
  @Test
  void anEmptyTableLeavesTheSequenceAlone() throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM " + TABLE);
    }

    runRepair();

    assertEquals(1L, nextValue());
  }

  /**
   * The per-vendor SQL, which cannot be executed here for want of an Oracle and
   * a PostgreSQL to hand — and which an upgrade, not a request, is what fails on
   * when it is wrong.
   */
  @Test
  void eachVendorGetsTheStatementItUnderstands() {
    SequenceRepairChange change = change();

    assertEquals(List.of("SELECT setval('seq_email_contact_address_id', 12, true)"),
                 change.statementsFor("postgresql", 12));
    assertEquals(List.of("ALTER SEQUENCE " + SEQUENCE + " RESTART WITH 13"), change.statementsFor("hsqldb", 12));
    assertEquals(List.of("DROP SEQUENCE " + SEQUENCE, "CREATE SEQUENCE " + SEQUENCE + " START WITH 13"),
                 change.statementsFor("oracle", 12));
    // MySQL's auto-increment moved itself when the seeded rows arrived.
    assertTrue(change.statementsFor("mysql", 12).isEmpty());
  }

  /**
   * Runs the change against the in-memory database.
   *
   * @throws Exception when the change fails
   */
  private void runRepair() throws Exception {
    HsqlDatabase database = new HsqlDatabase();
    database.setConnection(new JdbcConnection(connection));
    change().execute(database);
  }

  /**
   * The change under test, pointed at the fixture.
   *
   * @return the configured change
   */
  private SequenceRepairChange change() {
    SequenceRepairChange change = new SequenceRepairChange();
    change.setSequenceName(SEQUENCE);
    change.setTableName(TABLE);
    return change;
  }

  /**
   * Draws the next value from the sequence.
   *
   * @return the value drawn
   * @throws Exception when the sequence cannot be read
   */
  private long nextValue() throws Exception {
    try (Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery("VALUES NEXT VALUE FOR " + SEQUENCE)) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }

  /**
   * The value the sequence would hand out next, read without consuming it.
   *
   * @return the pending value
   * @throws Exception when the catalog cannot be read
   */
  private long peekNextValue() throws Exception {
    try (Statement statement = connection.createStatement();
         ResultSet resultSet =
                             statement.executeQuery("SELECT NEXT_VALUE FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_NAME = '"
                                 + SEQUENCE + "'")) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }
}
