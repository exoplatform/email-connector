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

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Advances a sequence past the largest id its table already holds.
 * <p>
 * Needed wherever a changeset seeded rows with explicit ids: the sequence that
 * hands out the NEXT id knows nothing about them and keeps counting from where
 * it was, so the first inserts after the upgrade collide with the seeded rows on
 * the primary key. MySQL is immune — its auto-increment counter moves itself
 * when an explicit id is inserted — which is exactly why this can sit unnoticed
 * in the vendor most deployments run.
 * <p>
 * Written as a custom change rather than three {@code <sql dbms="...">} blocks
 * because the value is not expressible in SQL on every vendor: PostgreSQL can
 * feed a subquery to {@code setval}, but HSQLDB's {@code ALTER SEQUENCE ...
 * RESTART WITH} takes a literal and nothing else. Reading the maximum in Java
 * once gives every vendor the literal it needs, and keeps one mechanism rather
 * than three dialects each getting the value from somewhere different.
 */
public class SequenceRepairChange implements CustomTaskChange {

  /** The sequence to advance, set from the changeset. */
  /** What a table or sequence name is allowed to look like before it reaches a statement. */
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

  private String sequenceName;

  /** The table whose ids it must clear, set from the changeset. */
  private String tableName;

  /** Records what the repair did, since an upgrade leaves no other trace of it. */
  private static final Log LOG = ExoLogger.getLogger(SequenceRepairChange.class);

  /**
   * Reads the table's largest id and moves the sequence beyond it.
   *
   * @param database the database Liquibase is running against
   * @throws CustomChangeException when the repair could not be applied
   */
  @Override
  public void execute(Database database) throws CustomChangeException {
    JdbcConnection connection = (JdbcConnection) database.getConnection();
    try (Statement statement = connection.createStatement()) {
      long max = readMaxId(statement);
      if (max <= 0) {
        // Nothing was seeded, so the sequence was never overtaken.
        return;
      }
      List<String> repair = statementsFor(database.getShortName(), max);
      for (String sql : repair) {
        statement.execute(sql);
      }
      LOG.info("Sequence {} advanced past id {} of table {}", sequenceName, max, tableName);
    } catch (Exception e) {
      throw new CustomChangeException("The sequence " + sequenceName + " could not be advanced past the ids of "
          + tableName, e);
    }
  }

  /**
   * A table or sequence name this change is willing to put in a statement.
   * <p>
   * JDBC cannot bind an identifier as a parameter, so the name is concatenated —
   * and the only defence that means anything is refusing anything that is not a
   * plain unquoted identifier. These names come from the changelog shipped in
   * this jar, never from a request, so a rejection here is a packaging mistake
   * rather than an attack, and failing the upgrade is the right answer to it.
   *
   * @param identifier the name declared in the changeset
   * @return the same name, once it is known to be a plain identifier
   */
  private static String plainIdentifier(String identifier) {
    if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
      throw new IllegalArgumentException("Not a plain SQL identifier: " + identifier);
    }
    return identifier;
  }

  /**
   * The largest id the table holds.
   *
   * @param statement an open statement on the upgrade's own connection
   * @return the maximum id, or 0 when the table is empty
   * @throws Exception when the query fails
   */
  private long readMaxId(Statement statement) throws Exception {
    try (ResultSet resultSet = statement.executeQuery("SELECT MAX(ID) FROM " + plainIdentifier(tableName))) {
      return resultSet.next() ? resultSet.getLong(1) : 0L;
    }
  }

  /**
   * The statements that move one vendor's sequence past a known id.
   * <p>
   * Package-visible so the per-vendor SQL can be asserted without a database of
   * each kind to hand — the one part of this change that is worth pinning, since
   * a wrong dialect here fails an upgrade rather than a request.
   *
   * @param shortName Liquibase's name for the vendor
   * @param max the largest id in the table, always above zero here
   * @return the statements to run in order, empty when the vendor needs none
   */
  List<String> statementsFor(String shortName, long max) {
    long next = max + 1;
    return switch (shortName == null ? "" : shortName.toLowerCase(Locale.ROOT)) {
      // is_called = true, so the next nextval() answers max + 1 rather than max.
      case "postgresql" -> List.of("SELECT setval('" + plainIdentifier(sequenceName).toLowerCase(Locale.ROOT) + "', " + max + ", true)");
      case "hsqldb" -> List.of("ALTER SEQUENCE " + plainIdentifier(sequenceName) + " RESTART WITH " + next);
      // Oracle only learned RESTART START WITH in 18c, and the older trick of
      // stepping the increment misses a sequence nobody has drawn from yet: the
      // first NEXTVAL answers START WITH whatever the increment says, so it would
      // still hand out a taken id. Recreating states the intended value outright
      // and behaves the same on every release. Safe here because the sequence is
      // the schema owner's and carries no grants.
      case "oracle" -> List.of("DROP SEQUENCE " + plainIdentifier(sequenceName),
                               "CREATE SEQUENCE " + plainIdentifier(sequenceName) + " START WITH " + next);
      // MySQL and anything else: the identity counter maintains itself.
      default -> List.of();
    };
  }

  /**
   * Sets the sequence to advance.
   *
   * @param name the sequence name
   */
  public void setSequenceName(String name) {
    this.sequenceName = name;
  }

  /**
   * Sets the table whose ids the sequence must clear.
   *
   * @param name the table name
   */
  public void setTableName(String name) {
    this.tableName = name;
  }

  /**
   * What the changelog reports for this change.
   *
   * @return the confirmation message
   */
  @Override
  public String getConfirmationMessage() {
    return "Sequence " + sequenceName + " advanced past the ids of " + tableName;
  }

  /**
   * Nothing to prepare.
   *
   * @throws SetupException never
   */
  @Override
  public void setUp() throws SetupException {
    // No state to build before the change runs.
  }

  /**
   * Not used: the change reads its own connection.
   *
   * @param resourceAccessor Liquibase's resource accessor
   */
  @Override
  public void setFileOpener(ResourceAccessor resourceAccessor) {
    // The change reads no file.
  }

  /**
   * Checks the changeset supplied both names.
   *
   * @param database the database Liquibase is running against
   * @return the errors found, empty when the change is usable
   */
  @Override
  public ValidationErrors validate(Database database) {
    ValidationErrors errors = new ValidationErrors();
    errors.checkRequiredField("sequenceName", sequenceName);
    errors.checkRequiredField("tableName", tableName);
    return errors;
  }
}
