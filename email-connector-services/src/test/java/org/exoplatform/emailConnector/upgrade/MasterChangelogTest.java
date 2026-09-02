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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import liquibase.Liquibase;
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
   * The custom-folder registry's three changesets (1.0.0-52 to 1.0.0-54) apply, roll
   * back, and apply again. Rolled back with the platform's own Liquibase rather than
   * by hand, because the org rule this pins is that a changeset's rollback is proven
   * before it ships -- an {@code update} or a {@code dropIndex} has no automatic
   * rollback and an empty {@code rollback} element silences a whole changeset's, and
   * neither mistake is visible in an apply-only run. The re-apply afterwards is what
   * shows the rollback left nothing behind (a surviving sequence or index would fail
   * the second CREATE).
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
      liquibase.update("");
      assertTrue(tableExists(connection, "EMAIL_FOLDER"), "1.0.0-52 creates EMAIL_FOLDER");
      liquibase.rollback(3, "");
      assertTrue(!tableExists(connection, "EMAIL_FOLDER"), "rolling back the last three changesets drops EMAIL_FOLDER");
      assertTrue(tableExists(connection, "EMAIL_THREAD_AI_SUMMARY"), "and nothing before them");
      liquibase.update("");
      assertTrue(tableExists(connection, "EMAIL_FOLDER"), "the changesets apply again after their rollback");
    }
  }

  /**
   * On MySQL, and only there, the registry's REMOTE_NAME keeps its case: generated
   * through Liquibase's own MySQL dialect (an offline connection, no server), the
   * CREATE TABLE of 1.0.0-52 carries a binary collation on that one column, while the
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
    // every changeset is generated, which is what makes 1.0.0-52's CREATE TABLE appear.
    java.nio.file.Path ledger = java.nio.file.Files.createTempFile("email-connector-mysql", ".csv");
    java.nio.file.Files.delete(ledger);
    java.io.StringWriter sql = new java.io.StringWriter();
    try {
      liquibase.database.Database mysql = DatabaseFactory.getInstance()
                                                         .openDatabase("offline:mysql?version=8.0.0&changeLogFile=" + ledger,
                                                                       null,
                                                                       null,
                                                                       null,
                                                                       new ClassLoaderResourceAccessor());
      new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), mysql).update(new liquibase.Contexts(), sql);
    } finally {
      java.nio.file.Files.deleteIfExists(ledger);
    }
    String createFolder = java.util.Arrays.stream(sql.toString().split(";"))
                                          .filter(statement -> statement.contains("CREATE TABLE EMAIL_FOLDER"))
                                          .findFirst()
                                          .orElseThrow(() -> new AssertionError("no CREATE TABLE EMAIL_FOLDER in the MySQL SQL"));
    assertTrue(createFolder.contains("REMOTE_NAME VARCHAR(500) COLLATE utf8mb4_bin"), createFolder);
    assertTrue(createFolder.contains("COLLATE utf8mb4_0900_ai_ci"), "the table keeps the file's collation: " + createFolder);
    assertTrue(!createFolder.contains("DISPLAY_NAME VARCHAR(255) COLLATE"), "only the identifier is binary: " + createFolder);
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
    try (java.sql.ResultSet tables = connection.getMetaData().getTables(null, null, tableName, null)) {
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
