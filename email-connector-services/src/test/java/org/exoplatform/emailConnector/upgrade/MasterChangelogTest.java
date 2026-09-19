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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import liquibase.ChecksumVersion;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.change.Change;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;

/**
 * Applies the whole changelog to an empty HSQLDB.
 * <p>
 * A changeset is not code anyone runs before a deployment does: a malformed one
 * fails the server it upgrades, at the worst possible moment, and nothing else
 * in this module would have noticed. This caught exactly that — a custom change
 * whose parameters were written as XML attributes, which Liquibase silently
 * ignores, leaving the change unconfigured and failing validation.
 * <p>
 * A fresh install rather than an upgrade of populated tables, so it says
 * nothing about migrating live data; what it does say is that every changeset
 * parses, and that the vendor-specific ones agree with each other.
 */
public class MasterChangelogTest {

  /** The changelog the add-on ships, as {@code emailConnector.properties} names it. */
  private static final String CHANGELOG = "db/changelog/emailConnector-rdbms.db.changelog-master.xml";

  /**
   * Runs every changeset, in order, on a database that has none of them.
   *
   * @throws Exception when a changeset does not apply
   */
  @Test
  void everyChangesetAppliesToAnEmptyDatabase() {
    assertDoesNotThrow(() -> {
      try (Connection connection = DriverManager.getConnection("jdbc:hsqldb:mem:changelog" + System.nanoTime(), "sa", "")) {
        Liquibase liquibase = new Liquibase(CHANGELOG,
                                            new ClassLoaderResourceAccessor(),
                                            DatabaseFactory.getInstance()
                                                           .findCorrectDatabaseImplementation(new JdbcConnection(connection)));
        liquibase.update("");
      }
    }, "every changeset of " + CHANGELOG + " must apply to an empty database");
  }

  /**
   * The custom-folder registry's changesets (1.0.0-53 to 1.0.0-56) apply, roll back,
   * and apply again. Rolled back with the platform's own Liquibase rather than by
   * hand, because the org rule this pins is that a changeset's rollback is proven
   * before it ships -- an {@code update} or a {@code dropIndex} has no automatic
   * rollback and an empty {@code rollback} element silences a whole changeset's, and
   * neither mistake is visible in an apply-only run. The re-apply afterwards is what
   * shows the rollback left nothing behind (a surviving sequence or index would fail
   * the second CREATE).
   * <p>
   * Rolled back to a tag placed immediately before 1.0.0-53, not by a changeset
   * count (EXO-89940): {@code rollback(int, ...)} always undoes the last N changesets
   * recorded at the time it runs, counting back from whatever the changelog's current
   * tail happens to be -- a fixed "3" silently rolled back 1.0.0-57, -55 and -54 the
   * moment 1.0.0-57 became the new tail, leaving EMAIL_FOLDER (1.0.0-53) standing and
   * this test failing on the opposite of what it meant to prove. A tag names a point
   * in the applied history rather than an offset from the end of it, so
   * {@code rollback(tag, ...)} keeps undoing exactly the registry regardless of how
   * many changesets end up appended after it.
   *
   * @throws Exception when a changeset does not apply or roll back
   */
  @Test
  void theFolderRegistryChangesetsRollBackAndReapply() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:hsqldb:mem:rollback" + System.nanoTime(), "sa", "")) {
      Liquibase liquibase = new Liquibase(CHANGELOG,
                                          new ClassLoaderResourceAccessor(),
                                          DatabaseFactory.getInstance()
                                                         .findCorrectDatabaseImplementation(new JdbcConnection(connection)));
      liquibase.update(applicableChangeSetsBefore("1.0.0-53"), new Contexts(), new LabelExpression());
      liquibase.tag("before-folder-registry");
      liquibase.update("");
      assertTrue(tableExists(connection, "EMAIL_FOLDER"), "1.0.0-53 creates EMAIL_FOLDER");
      liquibase.rollback("before-folder-registry", "");
      assertTrue(!tableExists(connection, "EMAIL_FOLDER"), "rolling back to before the folder registry drops EMAIL_FOLDER");
      assertTrue(tableExists(connection, "EMAIL_THREAD_AI_SUMMARY"), "and nothing before them");
      liquibase.update("");
      assertTrue(tableExists(connection, "EMAIL_FOLDER"), "the changesets apply again after their rollback");
    }
  }

  /**
   * The sync-state table's changesets (1.0.0-58 and 1.0.0-59) apply, roll back and
   * apply again, to a tag placed immediately before 1.0.0-58 for the reason the test
   * above gives. The rollback is what a revert of the add-on relies on: the old code
   * ignores the table, but a table left behind would fail the next install's CREATE.
   * What is checked after the rollback is that the table is gone AND that the
   * registry before it still stands -- a rollback that went one changeset too far
   * would be the opposite of what it means to prove.
   *
   * @throws Exception when a changeset does not apply or roll back
   */
  @Test
  void theSyncStateChangesetsRollBackAndReapply() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:hsqldb:mem:rollback58" + System.nanoTime(), "sa", "")) {
      Liquibase liquibase = newLiquibase(connection);
      liquibase.update(applicableChangeSetsBefore("1.0.0-58"), new Contexts(), new LabelExpression());
      liquibase.tag("before-sync-state");
      assertFalse(tableExists(connection, "EMAIL_SYNC_STATE"), "sanity: the table does not exist before 1.0.0-58");
      liquibase.update("");
      assertTrue(tableExists(connection, "EMAIL_SYNC_STATE"), "1.0.0-58 creates EMAIL_SYNC_STATE");
      assertTrue(indexExists(connection, "EMAIL_SYNC_STATE", "IDX_EMAIL_SYNC_STATE_LAST_SYNC"), "and its index");
      liquibase.rollback("before-sync-state", "");
      assertFalse(tableExists(connection, "EMAIL_SYNC_STATE"), "rolling back to before the sync state drops EMAIL_SYNC_STATE");
      assertTrue(tableExists(connection, "EMAIL_FOLDER"), "and nothing before it");
      liquibase.update("");
      assertTrue(tableExists(connection, "EMAIL_SYNC_STATE"), "the changesets apply again after their rollback");
    }
  }

  /**
   * The scheduled-send changesets (1.0.0-62 to 1.0.0-65) apply, roll back and apply
   * again, to a tag placed immediately before 1.0.0-62, for the reason
   * {@link #theFolderRegistryChangesetsRollBackAndReapply()} gives. After the rollback
   * the table, its sequence and its foreign key are gone and the sync-state table
   * before them still stands; the re-apply is what shows nothing was left behind (a
   * surviving sequence, index or constraint would fail the second CREATE). The cascade
   * itself is proven on the applied schema: deleting a draft row removes its schedule.
   *
   * @throws Exception when a changeset does not apply or roll back
   */
  @Test
  void theScheduledSendChangesetsRollBackAndReapply() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:hsqldb:mem:rollback62" + System.nanoTime(), "sa", "")) {
      Liquibase liquibase = newLiquibase(connection);
      liquibase.update(applicableChangeSetsBefore("1.0.0-62"), new Contexts(), new LabelExpression());
      liquibase.tag("before-scheduled-send");
      assertFalse(tableExists(connection, "EMAIL_SCHEDULED_SEND"), "sanity: the table does not exist before 1.0.0-63");
      liquibase.update("");
      assertTrue(tableExists(connection, "EMAIL_SCHEDULED_SEND"), "1.0.0-63 creates EMAIL_SCHEDULED_SEND");
      assertTrue(indexExists(connection, "EMAIL_SCHEDULED_SEND", "IDX_EMAIL_SCHED_SEND_DUE"), "and its due index");
      assertTrue(indexExists(connection, "EMAIL_SCHEDULED_SEND", "IDX_EMAIL_SCHED_SEND_USER"), "and its user index");
      assertTrue(sequenceExists(connection, "SEQ_EMAIL_SCHEDULED_SEND_ID"), "1.0.0-62 creates its sequence");
      assertDraftDeleteCascadesToItsSchedule(connection);

      liquibase.rollback("before-scheduled-send", "");
      assertFalse(tableExists(connection, "EMAIL_SCHEDULED_SEND"), "rolling back drops EMAIL_SCHEDULED_SEND");
      assertFalse(sequenceExists(connection, "SEQ_EMAIL_SCHEDULED_SEND_ID"), "and its sequence");
      assertTrue(tableExists(connection, "EMAIL_SYNC_STATE"), "and nothing before them");

      liquibase.update("");
      assertTrue(tableExists(connection, "EMAIL_SCHEDULED_SEND"), "the changesets apply again after their rollback");
      assertDraftDeleteCascadesToItsSchedule(connection);
    }
  }

  /**
   * The scheduled-send table as MySQL and PostgreSQL get it, generated through
   * Liquibase's own dialects on offline connections (no server): on MySQL the table
   * options arrive as literal SQL and BEFORE the foreign key (which needs InnoDB on both
   * sides), and on both vendors the foreign key carries ON DELETE CASCADE -- the one
   * property every bulk removal of a draft relies on. PostgreSQL gets the sequence the
   * entity's {@code @PortableSequence} names; MySQL gets an auto-increment instead.
   * Rollback SQL is generated too, and drops what the update created.
   *
   * @throws Exception when the SQL cannot be generated
   */
  @Test
  void theScheduledSendTableOnMySqlAndPostgreSql() throws Exception {
    String mysql = offlineUpdateSql("mysql?version=8.0.17", "1.0.0-62");
    int options = mysql.indexOf("ALTER TABLE EMAIL_SCHEDULED_SEND ENGINE=INNODB, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
    int foreignKey = mysql.indexOf("ADD CONSTRAINT FK_EMAIL_SCHED_SEND_EMAIL FOREIGN KEY (EMAIL_ID) REFERENCES EMAIL_BOX (ID) ON DELETE CASCADE");
    assertTrue(options > 0, "the table options of 1.0.0-64: " + mysql);
    assertTrue(foreignKey > options, "the cascading foreign key of 1.0.0-65, after the table options: " + foreignKey + " / " + options);
    Matcher createMysql = Pattern.compile("CREATE TABLE EMAIL_SCHEDULED_SEND \\(.*?\\)[^;]*", Pattern.DOTALL).matcher(mysql);
    assertTrue(createMysql.find(), "no CREATE TABLE EMAIL_SCHEDULED_SEND in the MySQL SQL");
    assertTrue(createMysql.group().contains("AUTO_INCREMENT"), "MySQL ids come from an auto-increment: " + createMysql.group());
    assertTrue(createMysql.group().contains("CONSTRAINT UQ_EMAIL_SCHED_SEND_EMAIL UNIQUE (EMAIL_ID)"), createMysql.group());
    assertFalse(mysql.contains("SEQ_EMAIL_SCHEDULED_SEND_ID"), "no sequence on MySQL");

    String postgresql = offlineUpdateSql("postgresql?version=15", "1.0.0-62");
    assertTrue(postgresql.contains("CREATE SEQUENCE  IF NOT EXISTS SEQ_EMAIL_SCHEDULED_SEND_ID START WITH 1"),
               "the sequence of 1.0.0-62 on PostgreSQL: " + postgresql);
    assertTrue(postgresql.indexOf("CREATE SEQUENCE") < postgresql.indexOf("CREATE TABLE EMAIL_SCHEDULED_SEND (ID BIGINT NOT NULL,"),
               "created before its table, with no auto-increment on the id: " + postgresql);
    assertTrue(postgresql.contains("ADD CONSTRAINT FK_EMAIL_SCHED_SEND_EMAIL FOREIGN KEY (EMAIL_ID) REFERENCES EMAIL_BOX (ID) ON DELETE CASCADE"),
               "the cascading foreign key on PostgreSQL: " + postgresql);

    for (String vendor : List.of("mysql?version=8.0.17", "postgresql?version=15")) {
      String rollback = offlineRollbackSql(vendor, "1.0.0-62").toUpperCase(Locale.ROOT);
      assertTrue(rollback.contains("DROP TABLE") && rollback.contains("EMAIL_SCHEDULED_SEND"), vendor + " rollback drops the table: " + rollback);
      assertTrue(rollback.indexOf("FK_EMAIL_SCHED_SEND_EMAIL") >= 0 && rollback.indexOf("FK_EMAIL_SCHED_SEND_EMAIL") < rollback.indexOf("DROP TABLE"),
                 vendor + " rollback drops the foreign key first: " + rollback);
    }
    assertTrue(offlineRollbackSql("postgresql?version=15", "1.0.0-62").contains("DROP SEQUENCE SEQ_EMAIL_SCHEDULED_SEND_ID"),
               "and the sequence, where there is one");
  }

  /**
   * The read-receipt columns (1.0.0-66, EXO-90435) apply, roll back and apply again, to
   * a tag placed immediately before them. After the update an existing row has the safe
   * defaults (nothing asked, no Return-Path match) and a row inserted without the
   * columns gets them too; after the rollback all four columns are gone and the
   * scheduled-send table before them still stands; the re-apply is what shows nothing
   * was left behind (a surviving column would fail the second ADD).
   *
   * @throws Exception when a changeset does not apply or roll back
   */
  @Test
  void theReadReceiptColumnsRollBackAndReapply() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:hsqldb:mem:rollback66" + System.nanoTime(), "sa", "")) {
      Liquibase liquibase = newLiquibase(connection);
      liquibase.update(applicableChangeSetsBefore("1.0.0-66"), new Contexts(), new LabelExpression());
      liquibase.tag("before-read-receipts");
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("INSERT INTO EMAIL_BOX (ID, USER_ID, SUBJECT, SENDER, RECEIVED_DATE, FOLDER) VALUES (9101, 'alice', 's',"
            + " 'Bob,bob@example.org', CURRENT_TIMESTAMP, 'INBOX')");
      }
      assertFalse(columnExists(connection, "EMAIL_BOX", "READ_RECEIPT_REQUESTED"), "sanity: not there before 1.0.0-66");
      liquibase.update("");
      for (String column : READ_RECEIPT_COLUMNS) {
        assertTrue(columnExists(connection, "EMAIL_BOX", column), "1.0.0-66 adds " + column);
      }
      assertReadReceiptDefaults(connection);

      liquibase.rollback("before-read-receipts", "");
      for (String column : READ_RECEIPT_COLUMNS) {
        assertFalse(columnExists(connection, "EMAIL_BOX", column), "rolling back drops " + column);
      }
      assertTrue(tableExists(connection, "EMAIL_SCHEDULED_SEND"), "and nothing before it");

      liquibase.update("");
      assertTrue(columnExists(connection, "EMAIL_BOX", "READ_RECEIPT_STATE"), "the changeset applies again after its rollback");
      assertReadReceiptDefaults(connection);
    }
  }

  /**
   * The read-receipt columns as MySQL and PostgreSQL get them, generated through
   * Liquibase's own dialects on offline connections: the two flags NOT NULL with a
   * false default (so every existing row is filled in the same statement), the two
   * others nullable; and the rollback drops each of the four.
   *
   * @throws Exception when the SQL cannot be generated
   */
  @Test
  void theReadReceiptColumnsOnMySqlAndPostgreSql() throws Exception {
    for (String vendor : List.of("mysql?version=8.0.17", "postgresql?version=15")) {
      String update = offlineUpdateSql(vendor, "1.0.0-66").toUpperCase(Locale.ROOT);
      // MySQL renders the flags TINYINT DEFAULT 0, PostgreSQL BOOLEAN DEFAULT FALSE: the
      // types every other flag of EMAIL_BOX already has on each.
      for (String flag : List.of("READ_RECEIPT_REQUESTED", "READ_RECEIPT_RETURN_PATH_MATCH")) {
        assertTrue(Pattern.compile("ADD " + flag + " (TINYINT DEFAULT 0|BOOLEAN DEFAULT FALSE) NOT NULL").matcher(update).find(),
                   vendor + " " + flag + ": " + update);
      }
      for (String nullable : List.of("READ_RECEIPT_TO N?VARCHAR\\(1000\\)", "READ_RECEIPT_STATE N?VARCHAR\\(20\\)")) {
        Matcher column = Pattern.compile("ADD " + nullable + "[^,;]*").matcher(update);
        assertTrue(column.find() && !column.group().contains("NOT NULL"), vendor + " " + nullable + ": " + update);
      }
      String rollback = offlineRollbackSql(vendor, "1.0.0-66").toUpperCase(Locale.ROOT);
      for (String column : READ_RECEIPT_COLUMNS) {
        assertTrue(rollback.contains("DROP COLUMN " + column), vendor + " rollback drops " + column + ": " + rollback);
      }
    }
  }

  // The four columns 1.0.0-66 adds to EMAIL_BOX.
  private static final List<String> READ_RECEIPT_COLUMNS = List.of("READ_RECEIPT_REQUESTED",
                                                                   "READ_RECEIPT_TO",
                                                                   "READ_RECEIPT_STATE",
                                                                   "READ_RECEIPT_RETURN_PATH_MATCH");

  /**
   * The row inserted before 1.0.0-66, and one inserted after without naming the new
   * columns, both carry the safe defaults: nothing asked, no match, no answer.
   *
   * @param connection the database
   * @throws SQLException when a statement fails
   */
  private void assertReadReceiptDefaults(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM EMAIL_BOX WHERE ID = 9102");
      statement.executeUpdate("INSERT INTO EMAIL_BOX (ID, USER_ID, SUBJECT, SENDER, RECEIVED_DATE, FOLDER) VALUES (9102, 'alice', 's',"
          + " 'Bob,bob@example.org', CURRENT_TIMESTAMP, 'INBOX')");
      try (ResultSet rows = statement.executeQuery("SELECT READ_RECEIPT_REQUESTED, READ_RECEIPT_RETURN_PATH_MATCH, READ_RECEIPT_TO,"
          + " READ_RECEIPT_STATE FROM EMAIL_BOX WHERE ID IN (9101, 9102)")) {
        int count = 0;
        while (rows.next()) {
          count++;
          assertFalse(rows.getBoolean(1));
          assertFalse(rows.getBoolean(2));
          assertNull(rows.getString(3));
          assertNull(rows.getString(4));
        }
        assertEquals(2, count);
      }
    }
  }

  /**
   * Whether a column exists, asked of the JDBC metadata.
   *
   * @param connection the database
   * @param tableName the table
   * @param columnName the column
   * @return true when it exists
   * @throws SQLException when the metadata cannot be read
   */
  private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
    try (ResultSet columns = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
      return columns.next();
    }
  }

  /**
   * Inserts a draft row and its schedule, deletes the draft row, and requires the
   * schedule row to be gone.
   *
   * @param connection the database
   * @throws SQLException when a statement fails
   */
  private void assertDraftDeleteCascadesToItsSchedule(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO EMAIL_BOX (ID, USER_ID, SUBJECT, SENDER, RECEIVED_DATE, FOLDER) VALUES (9001, 'alice', 's',"
          + " 'Alice,alice@example.org', CURRENT_TIMESTAMP, 'DRAFTS')");
      statement.executeUpdate("INSERT INTO EMAIL_SCHEDULED_SEND (ID, EMAIL_ID, USER_ID, DRAFT_LOCAL_ID, SCHEDULED_DATE, STATUS,"
          + " CREATED_DATE) VALUES (9002, 9001, 'alice', 'd', CURRENT_TIMESTAMP, 'SCHEDULED', CURRENT_TIMESTAMP)");
      statement.executeUpdate("DELETE FROM EMAIL_BOX WHERE ID = 9001");
      try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM EMAIL_SCHEDULED_SEND")) {
        rows.next();
        assertEquals(0, rows.getInt(1), "the schedule goes with its draft");
      }
    }
  }

  /**
   * Whether a sequence exists, asked of HSQLDB's information schema.
   *
   * @param connection the database
   * @param sequenceName the sequence, as created
   * @return true when it exists
   * @throws SQLException when the schema cannot be read
   */
  private boolean sequenceExists(Connection connection, String sequenceName) throws SQLException {
    try (PreparedStatement select = connection.prepareStatement("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_NAME = ?")) {
      select.setString(1, sequenceName);
      try (ResultSet result = select.executeQuery()) {
        result.next();
        return result.getInt(1) > 0;
      }
    }
  }

  /**
   * The SQL a vendor's database would run for the changesets from an id to the end,
   * generated by Liquibase's own SQL generators for that vendor over an offline
   * connection -- no server, and nothing executed, so vendor-specific custom changes
   * elsewhere in the file (which need a live connection) are not involved.
   *
   * @param vendor the offline URL's vendor part, e.g. {@code mysql?version=8.0.17}
   * @param fromId the first changeset whose SQL is generated
   * @return the SQL, one statement per line
   * @throws Exception when it cannot be generated
   */
  private String offlineUpdateSql(String vendor, String fromId) throws Exception {
    StringBuilder sql = new StringBuilder();
    Database database = offlineDatabase(vendor);
    for (ChangeSet changeSet : changeSetsFrom(database, vendor, fromId)) {
      for (Change change : changeSet.getChanges()) {
        appendSql(sql, change.generateStatements(database), database);
      }
    }
    return sql.toString();
  }

  /**
   * The rollback SQL of the changesets from an id to the end, last first, as the
   * vendor's generators render each changeset's declared rollback.
   *
   * @param vendor the offline URL's vendor part
   * @param fromId the first changeset rolled back
   * @return the SQL, one statement per line
   * @throws Exception when it cannot be generated
   */
  private String offlineRollbackSql(String vendor, String fromId) throws Exception {
    StringBuilder sql = new StringBuilder();
    Database database = offlineDatabase(vendor);
    List<ChangeSet> changeSets = new ArrayList<>(changeSetsFrom(database, vendor, fromId));
    java.util.Collections.reverse(changeSets);
    for (ChangeSet changeSet : changeSets) {
      for (Change change : changeSet.getRollback().getChanges()) {
        appendSql(sql, change.generateStatements(database), database);
      }
    }
    return sql.toString();
  }

  /**
   * An offline database of a vendor.
   *
   * @param vendor the offline URL's vendor part
   * @return the database
   * @throws Exception when it cannot be opened
   */
  private Database offlineDatabase(String vendor) throws Exception {
    return DatabaseFactory.getInstance()
                          .openDatabase("offline:" + vendor, null, null, null, new ClassLoaderResourceAccessor());
  }

  /**
   * The changesets from an id to the end that run on a vendor, in file order.
   *
   * @param database the offline database, for parsing
   * @param vendor the offline URL's vendor part
   * @param fromId the first changeset
   * @return the changesets
   * @throws Exception when the changelog cannot be parsed
   */
  private List<ChangeSet> changeSetsFrom(Database database, String vendor, String fromId) throws Exception {
    String dbms = vendor.substring(0, vendor.indexOf('?'));
    List<ChangeSet> selected = new ArrayList<>();
    boolean from = false;
    for (ChangeSet changeSet : new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database).getDatabaseChangeLog()
                                                                                                    .getChangeSets()) {
      // Compared by number, not by equality: a changeset another vendor filters out at
      // parse time (1.0.0-62 on MySQL) never appears in this list to be matched.
      from = from || sequenceNumber(changeSet.getId()) >= sequenceNumber(fromId);
      if (from && appliesTo(changeSet, dbms)) {
        selected.add(changeSet);
      }
    }
    return selected;
  }

  /**
   * The number at the end of a {@code 1.0.0-N} changeset id.
   *
   * @param id the id
   * @return N
   */
  private static int sequenceNumber(String id) {
    return Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
  }

  /**
   * Renders statements through the vendor's generators and appends them.
   *
   * @param sql the buffer
   * @param statements the statements
   * @param database the vendor
   */
  private void appendSql(StringBuilder sql, SqlStatement[] statements, Database database) {
    for (SqlStatement statement : statements) {
      for (Sql generated : SqlGeneratorFactory.getInstance().generateSql(statement, database)) {
        sql.append(generated.toSql()).append(";\n");
      }
    }
  }

  /**
   * Whether a parsed changeset runs on a vendor.
   *
   * @param changeSet the changeset
   * @param vendor the vendor's short name
   * @return true when its dbms list is empty or names the vendor
   */
  private boolean appliesTo(ChangeSet changeSet, String vendor) {
    return changeSet.getDbmsSet() == null || changeSet.getDbmsSet().isEmpty() || changeSet.getDbmsSet().contains(vendor);
  }

  /**
   * On MySQL, and only there, the registry's REMOTE_NAME keeps its case: generated
   * through Liquibase's own MySQL dialect (an offline connection, no server), the
   * CREATE TABLE of 1.0.0-53 carries a binary collation on that one column, while the
   * table keeps the file's accent-insensitive one. An IMAP folder name is
   * case-sensitive and a Gmail label is; under the table's collation "Projets" and
   * "projets" would be one row, and the lookup by name would answer the wrong folder.
   * The HSQLDB runs of this suite are case-sensitive and can never see that, which is
   * why this is asserted on the dialect's SQL rather than on a round trip.
   *
   * @throws Exception when the SQL cannot be generated
   */
  @Test
  void theRegistryNameKeepsItsCaseOnMySql() throws Exception {
    // An offline connection keeps its "already ran" ledger in a CSV; a fresh one means
    // every changeset is generated, which is what makes 1.0.0-53's CREATE TABLE appear.
    Path ledger = Files.createTempFile("email-connector-mysql", ".csv");
    Files.delete(ledger);
    StringWriter sql = new StringWriter();
    try {
      Database mysql = DatabaseFactory.getInstance()
                                      .openDatabase("offline:mysql?version=8.0.17&changeLogFile=" + ledger,
                                                    null,
                                                    null,
                                                    null,
                                                    new ClassLoaderResourceAccessor());
      new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), mysql).update(new Contexts(), sql);
    } finally {
      Files.deleteIfExists(ledger);
    }
    // The statement itself, not a split on semicolons: the changeset's comment above it
    // carries semicolons of its own.
    String generated = sql.toString();
    Matcher createFolder = Pattern.compile("CREATE TABLE EMAIL_FOLDER \\(.*?\\)[^;]*", Pattern.DOTALL).matcher(generated);
    assertTrue(createFolder.find(), "no CREATE TABLE EMAIL_FOLDER in the MySQL SQL");
    assertTrue(!createFolder.group().contains("COLLATE"), "the CREATE carries no modifySql of its own: " + createFolder.group());
    assertTrue(generated.contains("ALTER TABLE EMAIL_FOLDER ENGINE=INNODB, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"),
               "the table options of 1.0.0-56");
    assertTrue(generated.contains("ALTER TABLE EMAIL_FOLDER MODIFY REMOTE_NAME VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL"),
               "the binary collation on the identifier, and on it only");
    // The sync-state table takes the same options through the same device, 1.0.0-59.
    Matcher createSyncState = Pattern.compile("CREATE TABLE EMAIL_SYNC_STATE \\(.*?\\)[^;]*", Pattern.DOTALL).matcher(generated);
    assertTrue(createSyncState.find(), "no CREATE TABLE EMAIL_SYNC_STATE in the MySQL SQL");
    assertTrue(!createSyncState.group().contains("COLLATE"), "the CREATE carries no modifySql of its own: " + createSyncState.group());
    assertTrue(generated.contains("ALTER TABLE EMAIL_SYNC_STATE ENGINE=INNODB, CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"),
               "the table options of 1.0.0-59");
  }

  // The changesets this add-on's branches added since the checksum pin below exists
  // (the custom-folder registry, 1.0.0-53 to -56; the sync-state table, 1.0.0-58 and
  // -59; the scheduled-send table, 1.0.0-62 to -65; the read-receipt columns, 1.0.0-66). They are the ones a second evaluation computes ahead of the update in the
  // pin, and nothing on this list may ever drift.
  private static final Set<String> BRANCH_CHANGESETS = Set.of("1.0.0-53",
                                                              "1.0.0-54",
                                                              "1.0.0-55",
                                                              "1.0.0-56",
                                                              "1.0.0-58",
                                                              "1.0.0-59",
                                                              "1.0.0-62",
                                                              "1.0.0-63",
                                                              "1.0.0-64",
                                                              "1.0.0-65",
                                                              "1.0.0-66");

  // The changesets whose checksum already depends on where it is computed: every one
  // of them carries a modifySql. Three are covered by validCheckSum ANY (1.0.0-5, -46,
  // -48); 1.0.0-1, -2 and -27 are not, and have survived every restart so far only
  // because every evaluation of a running platform computed them the same way. They
  // are listed, not fixed: a validCheckSum for an id that already ran everywhere is a
  // decision about every deployment's recorded value, not this branch's. Nothing may
  // be ADDED to this list.
  private static final Set<String> KNOWN_SCOPE_DEPENDENT_CHECKSUMS =
                                                                   Set.of("1.0.0-1", "1.0.0-2", "1.0.0-5", "1.0.0-27", "1.0.0-46", "1.0.0-48");

  /**
   * A changeset's checksum must not depend on where it is computed. The shape of the
   * failure this pins: the platform started this add-on's Spring context twice in one
   * boot; the first applied the registry table and recorded the checksum it computed while
   * executing it, the second computed the same changeset outside that execution,
   * got another number, and refused the whole changelog, taking the portal down.
   * Liquibase serialises a changeset's modifySql visitors through a filter that reads
   * the checksum version off the current Scope, and a ChangeSet keeps the first value
   * it computed, so any modifySql changeset has two checksums: the one recorded by
   * the update that ran it and the one anything else computes.
   * <p>
   * So: apply the changelog, then compute every changeset's checksum from a fresh
   * parse OUTSIDE any update, and compare with what the update recorded; every
   * difference must be one of the pre-existing, listed ones. Then run the update
   * again on the same connection with those checksums already computed (which is
   * what a second context does) and require it to validate. A single-pass apply
   * cannot see any of this, which is why the rig saw it first.
   *
   * @throws Exception when the changelog cannot be applied or read back
   */
  @Test
  void aChecksumIsTheSameWhereverItIsComputed() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:hsqldb:mem:twice" + System.nanoTime(), "sa", "")) {
      Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
      new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database).update("");
      Map<String, String> recorded = new TreeMap<>();
      try (ResultSet rows = connection.createStatement().executeQuery("SELECT ID, MD5SUM FROM DATABASECHANGELOG")) {
        while (rows.next()) {
          recorded.put(rows.getString(1), rows.getString(2));
        }
      }
      Liquibase second = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
      Map<String, String> drifting = new TreeMap<>();
      for (ChangeSet changeSet : second.getDatabaseChangeLog().getChangeSets()) {
        String outside = changeSet.generateCheckSum(ChecksumVersion.latest()).toString();
        String stored = recorded.get(changeSet.getId());
        if (stored != null && !stored.equals(outside)) {
          drifting.put(changeSet.getId(), stored + " recorded, " + outside + " computed outside the update");
        }
      }
      drifting.keySet().removeAll(KNOWN_SCOPE_DEPENDENT_CHECKSUMS);
      assertTrue(drifting.isEmpty(),
                 () -> "these changesets have a checksum that depends on where it is computed; a second context, or the next restart,"
                     + " refuses the whole changelog: " + drifting);
      // The second evaluation, as the rig ran it: the changesets this branch adds have
      // their checksum computed before the update runs (a fresh parse, so the values
      // above are not carried over), then the update validates them against what the
      // first evaluation recorded. The pre-existing changesets are left to the update
      // itself, the way every real evaluation so far has computed them.
      Liquibase third = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database);
      for (ChangeSet changeSet : third.getDatabaseChangeLog().getChangeSets()) {
        if (BRANCH_CHANGESETS.contains(changeSet.getId())) {
          changeSet.generateCheckSum(ChecksumVersion.latest());
        }
      }
      assertDoesNotThrow(() -> third.update(""), "the second evaluation, with the checksums already computed, must validate");
    }
  }

  /**
   * 1.0.0-52 is burned and must never be reused: the index that is 1.0.0-24 today
   * carried that id on feature/ai-contribution between 20 and 23 August 2026, and the
   * databases that ran the branch then hold a 1.0.0-52 row for it. A changeset's
   * identity is filename plus id plus author, so a new 1.0.0-52 collides with that row
   * on every one of them -- which is how the registry's first deploy took the rig
   * down. Renumbering was right THIS time because the new changesets had run nowhere;
   * it is never right for an id that has.
   *
   * @throws Exception when the changelog cannot be read or parsed
   */
  @Test
  void theBurnedIdIsNeverReused() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    try (InputStream changelog = getClass().getClassLoader().getResourceAsStream(CHANGELOG)) {
      NodeList changeSets = factory.newDocumentBuilder().parse(changelog).getElementsByTagNameNS("*", "changeSet");
      for (int i = 0; i < changeSets.getLength(); i++) {
        assertTrue(!"1.0.0-52".equals(((Element) changeSets.item(i)).getAttribute("id")),
                   "1.0.0-52 was recorded on every database that ran feature/ai-contribution between 20 and 23 August 2026"
                       + " (as the index now at 1.0.0-24); a changeset under that id collides with all of them");
      }
    }
  }

  /**
   * Whether a table exists, asked of the JDBC metadata.
   *
   * @param connection the database
   * @param tableName the table, as created
   * @return true when the table is there
   * @throws Exception when the metadata cannot be read
   */
  private boolean tableExists(Connection connection, String tableName) throws Exception {
    try (ResultSet tables = connection.getMetaData().getTables(null, null, tableName, null)) {
      return tables.next();
    }
  }

  /**
   * Refuses a changeset that carries both a {@code createIndex} and a {@code modifySql}.
   * <p>
   * This is the one defect the test above cannot see. {@code modifySql} is scoped to
   * the CHANGESET, so an {@code <append>} written for a CREATE TABLE — the
   * " ENGINE=INNODB CHARSET=... COLLATE ..." this changelog puts on its tables — is
   * appended to every other statement the changeset emits as well. On a CREATE INDEX
   * that clause is a syntax error, and MySQL rejects it with error 1064.
   * <p>
   * It reached an acceptance server because the append carries {@code dbms="mysql"}:
   * the test above runs on HSQLDB, never generates the clause, and sees the changeset
   * apply perfectly. Everything downstream then fails at once — Liquibase stops, the
   * entityManagerFactory never builds, this add-on's Spring context never starts, and
   * through the Kernel/Spring bridge the portal itself does not come up.
   * <p>
   * Asserted on the changelog's structure rather than on generated SQL deliberately:
   * it needs no MySQL server and no offline snapshot, so it cannot be skipped or turn
   * flaky in a pipeline — and it fails on the mistake itself rather than on one of its
   * symptoms. Put the index in its own changeset, as every index from 1.0.0-19 onward
   * already is.
   *
   * @throws Exception when the changelog cannot be read or parsed
   */
  @Test
  void noChangesetAppendsTableOptionsToAnIndex() throws Exception {
    List<String> offenders = new ArrayList<>();
    // Namespace-aware on purpose: left off, getLocalName() returns null for every
    // element and the loop below silently matches nothing — a test that always passes.
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    try (InputStream changelog = getClass().getClassLoader().getResourceAsStream(CHANGELOG)) {
      NodeList changeSets = factory.newDocumentBuilder()
                                   .parse(changelog)
                                   .getElementsByTagNameNS("*", "changeSet");
      for (int i = 0; i < changeSets.getLength(); i++) {
        Element changeSet = (Element) changeSets.item(i);
        if (hasChild(changeSet, "createIndex") && hasChild(changeSet, "modifySql")) {
          offenders.add(changeSet.getAttribute("id"));
        }
      }
    }
    assertTrue(offenders.isEmpty(),
               () -> "modifySql is changeset-scoped, so its append also lands on CREATE INDEX, "
                   + "which MySQL rejects (error 1064). Move the index to its own changeset. Offending changesets: "
                   + offenders);
  }
  
  /**
   * The three changesets whose ids were reused for different content keep declaring
   * validCheckSum.
   * <p>
   * 1.0.0-22, -23 and -24 already ran on environments where the changelog held other
   * content under those ids: collapsing an upstream two-step history into one changeset
   * shifted every later id down by one. Their recorded checksums can therefore never
   * match again, and without validCheckSum Liquibase refuses the whole changelog — the
   * bean fails, the Spring context never starts, and the platform is down. That is how
   * the acceptance environment was lost, so the declarations are load-bearing rather
   * than decorative, and something a tidy-up would otherwise remove as noise.
   * <p>
   * Deliberately pinned to these three ids rather than asserted over the file at large:
   * validCheckSum is a repair for ids already spent, and a rule encouraging it anywhere
   * would wave through the next genuine mismatch.
   *
   * @throws Exception when the changelog cannot be read or parsed
   */
  @Test
  void reusedChangesetIdsKeepAcceptingTheirRecordedCheckSum() throws Exception {
    List<String> reusedIds = List.of("1.0.0-22", "1.0.0-23", "1.0.0-24");
    List<String> unprotected = new ArrayList<>();
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    try (InputStream changelog = getClass().getClassLoader().getResourceAsStream(CHANGELOG)) {
      NodeList changeSets = factory.newDocumentBuilder()
                                   .parse(changelog)
                                   .getElementsByTagNameNS("*", "changeSet");
      for (int i = 0; i < changeSets.getLength(); i++) {
        Element changeSet = (Element) changeSets.item(i);
        if (reusedIds.contains(changeSet.getAttribute("id")) && !hasChild(changeSet, "validCheckSum")) {
          unprotected.add(changeSet.getAttribute("id"));
        }
      }
    }
    assertTrue(unprotected.isEmpty(),
               () -> "These ids already ran carrying different content, so their recorded checksums can "
                   + "never match. Removing validCheckSum stops the platform booting against any database "
                   + "that ran the earlier changelog. Unprotected changesets: "
                   + unprotected);
  }

  /**
   * The mirror case of {@link #indexIsCreatedWhenTwentyFourWasRecordedAsTheOldAddColumn()}: a
   * database that recorded 1.0.0-24 carrying today's content (a fresh install, or one that
   * upgraded after 14 August) already has the index. 1.0.0-57 must not try to create it again
   * — it must mark itself ran and leave the schema alone.
   *
   * @throws Exception when the changelog cannot be read, parsed or applied
   */
  @Test
  void changesetMarksRanWhenTwentyFourAlreadyCreatedTheIndex() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:hsqldb:mem:changelog24new" + System.nanoTime(), "sa", "")) {
      Liquibase liquibase = newLiquibase(connection);

      liquibase.update("");

      assertTrue(indexExists(connection), "sanity: a fresh install must already carry the index via 1.0.0-24");
      assertEquals("MARK_RAN", execType(connection, "1.0.0-57"),
                   "1.0.0-57 must mark itself ran, not re-create the index 1.0.0-24 already created");
    }
  }

  /**
   * A fresh {@link Liquibase} instance bound to the add-on's changelog and the given
   * connection.
   *
   * @param connection the JDBC connection to apply the changelog against
   * @return a ready-to-use {@link Liquibase} instance
   * @throws Exception when the database implementation cannot be resolved
   */
  private Liquibase newLiquibase(Connection connection) throws Exception {
    return new Liquibase(CHANGELOG,
                          new ClassLoaderResourceAccessor(),
                          DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)));
  }

  /**
   * How many of the changelog's changesets, in document order and dbms-filtered for hsqldb,
   * appear strictly before the given id.
   * <p>
   * Mirrors Liquibase's own {@code DbmsChangeSetFilter}: a changeset with no {@code dbms}
   * attribute applies to every database, one that has it applies only when the attribute's
   * comma-separated list contains {@code hsqldb}. Passing the result to
   * {@link Liquibase#update(int, Contexts, LabelExpression)} therefore applies exactly the
   * changesets a real HSQLDB database would already have run by the time it reached
   * {@code beforeId} — the same count Liquibase's internal {@code CountChangeSetFilter} would
   * stop at.
   *
   * @param beforeId the id to stop counting at (not itself counted)
   * @return the number of hsqldb-applicable changesets preceding {@code beforeId}
   * @throws Exception when the changelog cannot be read or parsed
   */
  private int applicableChangeSetsBefore(String beforeId) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    int count = 0;
    try (InputStream changelog = getClass().getClassLoader().getResourceAsStream(CHANGELOG)) {
      NodeList changeSets = factory.newDocumentBuilder()
                                   .parse(changelog)
                                   .getElementsByTagNameNS("*", "changeSet");
      for (int i = 0; i < changeSets.getLength(); i++) {
        Element changeSet = (Element) changeSets.item(i);
        if (beforeId.equals(changeSet.getAttribute("id"))) {
          break;
        }
        if (appliesToHsqldb(changeSet)) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * Whether a changeset's {@code dbms} attribute (absent, or containing {@code hsqldb}) lets
   * it run on HSQLDB.
   *
   * @param changeSet the changeset element to inspect
   * @return true when the changeset applies to hsqldb
   */
  private boolean appliesToHsqldb(Element changeSet) {
    String dbms = changeSet.getAttribute("dbms");
    if (dbms == null || dbms.isBlank()) {
      return true;
    }
    for (String candidate : dbms.split(",")) {
      if ("hsqldb".equals(candidate.trim().toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code IDX_EMAIL_BOX_USER_FOLDER_DATE} exists on EMAIL_BOX, read from the JDBC
   * driver's own metadata rather than an HSQLDB-specific system table, so it holds regardless
   * of the HSQLDB version running the test.
   *
   * @param connection the JDBC connection to inspect
   * @return true when the index exists
   * @throws SQLException when the driver metadata cannot be read
   */
  private boolean indexExists(Connection connection) throws SQLException {
    return indexExists(connection, "EMAIL_BOX", "IDX_EMAIL_BOX_USER_FOLDER_DATE");
  }

  /**
   * Whether an index exists on a table, read from the JDBC driver's own metadata.
   *
   * @param connection the JDBC connection to inspect
   * @param tableName the table, as created
   * @param indexName the index, as created
   * @return true when the index exists
   * @throws SQLException when the driver metadata cannot be read
   */
  private boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
    try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
      while (indexes.next()) {
        if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * The {@code EXECTYPE} DATABASECHANGELOG recorded for a changeset id (e.g. {@code EXECUTED}
   * or {@code MARK_RAN}), asserting there is exactly one such row.
   *
   * @param connection the JDBC connection to query
   * @param id the changeset id to look up
   * @return the recorded EXECTYPE
   * @throws SQLException when the query fails or the id has no (or more than one) row
   */
  private String execType(Connection connection, String id) throws SQLException {
    try (PreparedStatement select = connection.prepareStatement(
        "SELECT EXECTYPE FROM DATABASECHANGELOG WHERE ID = ? AND AUTHOR = 'email-connector'")) {
      select.setString(1, id);
      try (ResultSet result = select.executeQuery()) {
        assertTrue(result.next(), () -> "no DATABASECHANGELOG row recorded for id " + id);
        String execType = result.getString("EXECTYPE");
        assertFalse(result.next(), () -> "more than one DATABASECHANGELOG row recorded for id " + id);
        return execType;
      }
    }
  }

  /**
   * Whether a changeset holds a direct child element of the given name.
   * <p>
   * Direct children only: a name nested deeper belongs to some other change, and
   * counting it would report a changeset that is in fact well formed.
   *
   * @param changeSet the changeset to inspect
   * @param name the unqualified element name to look for
   * @return true when the changeset has such a child
   */
  private boolean hasChild(Element changeSet, String name) {
    NodeList children = changeSet.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getLocalName())) {
        return true;
      }
    }
    return false;
  }
}