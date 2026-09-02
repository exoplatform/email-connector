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
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

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
  }

  // The changesets whose checksum already depends on where it is computed: every one
  // of them carries a modifySql. Three are covered by validCheckSum ANY (1.0.0-5, -46,
  // -48); 1.0.0-1, -2 and -27 are not, and have survived every restart so far only
  // because every evaluation of a running platform computed them the same way. They
  // are listed, not fixed: a validCheckSum for an id that already ran everywhere is a
  // decision about every deployment's recorded value, not this branch's. Nothing may
  // be ADDED to this list.
  // The changesets this branch adds. They are the ones a second evaluation computes
  // ahead of the update in the pin below, and nothing on this list may ever drift.
  private static final Set<String> BRANCH_CHANGESETS = Set.of("1.0.0-53", "1.0.0-54", "1.0.0-55", "1.0.0-56");

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
   * The id 1.0.0-24 has held two different changesets over time (see its own comment and
   * 1.0.0-57's, EXO-89940): before 14 August it was the ORIGINAL_SENDER {@code addColumn},
   * today it is {@code createIndex IDX_EMAIL_BOX_USER_FOLDER_DATE}. Liquibase decides whether
   * to re-run 1.0.0-57 purely from what the database recorded under 24 — never from what the
   * changelog carries today — so the only test that can see the defect is one that first
   * recreates that older recorded history, then lets the real changelog run over it.
   * <p>
   * On such a database, the index was never created by 1.0.0-24 (Liquibase ticked the id off
   * without ever running today's content), so 1.0.0-57's precondition finds it missing and
   * must create it.
   *
   * @throws Exception when the changelog cannot be read, parsed or applied
   */
  @Test
  void indexIsCreatedWhenTwentyFourWasRecordedAsTheOldAddColumn() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:hsqldb:mem:changelog24old" + System.nanoTime(), "sa", "")) {
      Liquibase liquibase = newLiquibase(connection);

      // Apply exactly the changesets a database that stopped right before 1.0.0-24 would
      // already carry - counted dynamically because the changelog's own dbms filtering
      // (oracle/postgresql/hsqldb/mysql-only changesets) decides which of them actually run
      // on HSQLDB, same as Liquibase's own CountChangeSetFilter does internally.
      liquibase.update(applicableChangeSetsBefore("1.0.0-24"), new Contexts(), new LabelExpression());
      assertFalse(indexExists(connection), "sanity: the index must not exist before 1.0.0-24 has run at all");

      // Record 1.0.0-24 exactly as a pre-14-August database would have: its id ticked off,
      // carrying the old addColumn content, the index never created.
      recordChangeSetAsAlreadyRan(connection, "1.0.0-24",
                                   "addColumn tableName=EMAIL_BOX (ORIGINAL_SENDER) -- simulated pre-14-Aug history");
      assertFalse(indexExists(connection), "the simulated old history must still be missing the index");

      liquibase.update("");

      assertTrue(indexExists(connection),
                 "1.0.0-57 must create IDX_EMAIL_BOX_USER_FOLDER_DATE when 1.0.0-24 was recorded "
                     + "under its pre-14-Aug addColumn content");
      assertEquals("EXECUTED", execType(connection, "1.0.0-57"),
                   "1.0.0-57 must actually run (not merely mark itself ran) when the index is missing");
    }
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
   * Inserts a DATABASECHANGELOG row directly over JDBC, bypassing Liquibase entirely, to
   * simulate a database that already recorded the given changeset id under different
   * (unspecified) content — exactly what an id reused across a changelog rewrite leaves
   * behind. The checksum recorded is deliberately arbitrary: 1.0.0-24 declares
   * {@code validCheckSum ANY}, so Liquibase must accept it regardless, same as it does on a
   * real database carrying the pre-14-Aug history.
   *
   * @param connection the JDBC connection whose DATABASECHANGELOG table to write into
   * @param id the changeset id to record as already run
   * @param description a human-readable note of what the simulated history actually ran
   * @throws SQLException when the insert fails
   */
  private void recordChangeSetAsAlreadyRan(Connection connection, String id, String description) throws SQLException {
    int nextOrder;
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT MAX(ORDEREXECUTED) FROM DATABASECHANGELOG")) {
      result.next();
      nextOrder = result.getInt(1) + 1;
    }
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO DATABASECHANGELOG "
            + "(ID, AUTHOR, FILENAME, DATEEXECUTED, ORDEREXECUTED, EXECTYPE, MD5SUM, DESCRIPTION, COMMENTS, LIQUIBASE, DEPLOYMENT_ID) "
            + "VALUES (?, 'email-connector', ?, CURRENT_TIMESTAMP, ?, 'EXECUTED', '9:simulated', ?, ?, '4.31.1', '9999999999')")) {
      insert.setString(1, id);
      insert.setString(2, CHANGELOG);
      insert.setInt(3, nextOrder);
      insert.setString(4, description);
      insert.setString(5, "Recorded directly by " + getClass().getSimpleName()
          + " to simulate a database that ran an earlier changelog (EXO-89940).");
      insert.executeUpdate();
    }
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
    try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, "EMAIL_BOX", false, false)) {
      while (indexes.next()) {
        if ("IDX_EMAIL_BOX_USER_FOLDER_DATE".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
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
