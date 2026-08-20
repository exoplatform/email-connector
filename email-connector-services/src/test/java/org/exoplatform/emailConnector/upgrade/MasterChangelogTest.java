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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

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
  void everyChangesetAppliesToAnEmptyDatabase() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:hsqldb:mem:changelog" + System.nanoTime(), "sa", "")) {
      Liquibase liquibase = new Liquibase(CHANGELOG,
                                          new ClassLoaderResourceAccessor(),
                                          DatabaseFactory.getInstance()
                                                         .findCorrectDatabaseImplementation(new JdbcConnection(connection)));
      liquibase.update("");
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
   * A database holding content whose id was never recorded still updates.
   * <p>
   * This is the acceptance failure, reproduced. The checksum repair let the boot get past
   * 1.0.0-22 to -24 and it then died on 1.0.0-26: on MySQL the earlier numbering had added
   * STARRED under 1.0.0-25, so the column was present while 1.0.0-26 held no row to accept.
   * Liquibase ran the change and the server rejected a duplicate column, the liquibase bean
   * failed, and the platform was down.
   * <p>
   * Deleting the row after a clean run reproduces exactly that shape — the change applied,
   * its id unrecorded — without needing a database that followed the old numbering. Before
   * the precondition this throws; with it the changeset marks itself ran and the update
   * completes. 1.0.0-52 is exercised the same way, being the same kind of guard.
   *
   * @throws Exception when the changelog cannot be read or applied
   */
  @Test
  void aChangesetWhoseContentIsAlreadyThereMarksItselfRan() throws Exception {
    String url = "jdbc:hsqldb:mem:reused" + System.nanoTime();
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      newLiquibase(connection).update("");
      // The change is applied but its id is not recorded: what an environment looks like
      // when the content arrived under a different id.
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("DELETE FROM DATABASECHANGELOG WHERE ID IN ('1.0.0-26', '1.0.0-52')");
      }
      newLiquibase(connection).update("");
    }
  }

  /**
   * A Liquibase bound to an open connection, so a test can run the changelog twice against
   * one database.
   *
   * @param connection the open connection
   * @return the Liquibase instance
   * @throws Exception when the database implementation cannot be resolved
   */
  private Liquibase newLiquibase(Connection connection) throws Exception {
    return new Liquibase(CHANGELOG,
                         new ClassLoaderResourceAccessor(),
                         DatabaseFactory.getInstance()
                                        .findCorrectDatabaseImplementation(new JdbcConnection(connection)));
  }

  /**
   * The changesets whose ids were reused for different content keep declaring
   * validCheckSum.
   * <p>
   * 1.0.0-22 through -26 already ran on environments where the changelog held other
   * content under those ids: collapsing an upstream two-step history into one changeset
   * shifted every later id down by one, up to 1.0.0-27 where the two numberings meet
   * again. Their recorded checksums can therefore never match again, and without
   * validCheckSum Liquibase refuses the whole changelog — the bean fails, the Spring
   * context never starts, and the platform is down. That is how the acceptance
   * environment was lost, so the declarations are load-bearing rather than decorative,
   * and something a tidy-up would otherwise remove as noise.
   * <p>
   * -25 and -26 were added after the first repair: the outage reported three mismatches
   * rather than five because 1.0.0-25 carries {@code dbms="oracle,postgresql,hsqldb"} and
   * MySQL therefore recorded neither it nor, in the earlier numbering, -26. Those two ids
   * are exposed on the other three databases exactly as -22 to -24 are on every one.
   * <p>
   * Deliberately pinned to these ids rather than asserted over the file at large:
   * validCheckSum is a repair for ids already spent, and a rule encouraging it anywhere
   * would wave through the next genuine mismatch.
   *
   * @throws Exception when the changelog cannot be read or parsed
   */
  @Test
  void reusedChangesetIdsKeepAcceptingTheirRecordedCheckSum() throws Exception {
    List<String> reusedIds = List.of("1.0.0-22", "1.0.0-23", "1.0.0-24", "1.0.0-25", "1.0.0-26");
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
   * The changesets whose content may already be present under another id guard on the
   * object itself rather than on a checksum.
   * <p>
   * validCheckSum and a precondition answer two different questions, and the second
   * outage turned on the difference. validCheckSum accepts a row Liquibase already has:
   * it repairs an id that RAN carrying something else. It says nothing about an id that
   * never ran at all — Liquibase executes that one, and the database rejects a column,
   * sequence or index that is already there. 1.0.0-26 is exactly that case on MySQL,
   * where the earlier numbering had added STARRED under 1.0.0-25 and this id holds no
   * row to accept.
   * <p>
   * 1.0.0-52 is here for the mirror-image reason: it re-offers the index that 1.0.0-24
   * creates, because accepting 1.0.0-24's recorded checksum also means never running its
   * body. Without the precondition it would fail on every database that followed the
   * current numbering and already has the index.
   *
   * @throws Exception when the changelog cannot be read or parsed
   */
  @Test
  void changesetsWhoseContentMayAlreadyExistMarkThemselvesRanInstead() throws Exception {
    List<String> guardedIds = List.of("1.0.0-25", "1.0.0-26", "1.0.0-52");
    List<String> unguarded = new ArrayList<>();
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    try (InputStream changelog = getClass().getClassLoader().getResourceAsStream(CHANGELOG)) {
      NodeList changeSets = factory.newDocumentBuilder()
                                   .parse(changelog)
                                   .getElementsByTagNameNS("*", "changeSet");
      for (int i = 0; i < changeSets.getLength(); i++) {
        Element changeSet = (Element) changeSets.item(i);
        if (!guardedIds.contains(changeSet.getAttribute("id"))) {
          continue;
        }
        NodeList preConditions = changeSet.getElementsByTagNameNS("*", "preConditions");
        boolean marksRan = preConditions.getLength() > 0
            && "MARK_RAN".equals(((Element) preConditions.item(0)).getAttribute("onFail"));
        if (!marksRan) {
          unguarded.add(changeSet.getAttribute("id"));
        }
      }
    }
    assertTrue(unguarded.isEmpty(),
               () -> "These changesets carry content that another id may already have applied, so they must "
                   + "mark themselves ran rather than replay it — validCheckSum cannot help an id that holds "
                   + "no recorded row. Unguarded changesets: "
                   + unguarded);
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
