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

import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.jupiter.api.Test;

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
}
