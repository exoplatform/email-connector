/**
 * Copyright (C) 2026 eXo Platform SAS
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
package org.exoplatform.emailConnector.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class EmailConnectorTest {

  /**
   * EXO-89650. The provider configuration carries the technical account's secret in
   * the clear on the way in; the generated toString would print it into any log
   * line or exception message the connector reaches.
   */
  @Test
  void aConnectorNeverPrintsItsProviderConfiguration() {
    EmailConnector emailConnector = new EmailConnector();
    emailConnector.setName("BlueMind");
    emailConnector.setProviderConfig(Map.of("technicalLogin", "svc-exo", "technicalSecret", "s3cr3t-technical"));

    String rendered = emailConnector.toString();

    assertFalse(rendered.contains("s3cr3t-technical"), rendered);
    assertTrue(rendered.contains("BlueMind"), rendered);
  }
}
