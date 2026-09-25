/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.service.bluemind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.model.EmailConnector;

/**
 * Where a preset's BlueMind core API is: the per-preset property, else the global one,
 * else the webmail's host with {@code /api}; never the IMAP host; https unless loopback.
 */
public class BlueMindEndpointTest {

  private static final long ID = 93L;

  /**
   * Clears the properties.
   */
  @AfterEach
  public void tearDown() {
    System.clearProperty(BlueMindEndpoint.CORE_URL_PROPERTY);
    System.clearProperty(BlueMindEndpoint.CORE_URL_PROPERTY + "." + ID);
  }

  /**
   * The webmail's scheme, host and port with {@code /api}, its own path dropped; the IMAP
   * host is never used.
   */
  @Test
  public void testTheDefaultIsTheWebmailHost() {
    EmailConnector connector = connector("https://webmail.demo3.livecollab.fr/webapp/mail/");
    connector.setImapUrl("imap.elsewhere.example");
    assertEquals("https://webmail.demo3.livecollab.fr/api", BlueMindEndpoint.apiRootOf(connector));
    assertEquals("https://mail.example.com:8443/api", BlueMindEndpoint.apiRootOf(connector("https://mail.example.com:8443")));
    EmailConnector noWebmail = connector(null);
    noWebmail.setImapUrl("imap.elsewhere.example");
    assertThrows(IllegalStateException.class, () -> BlueMindEndpoint.apiRootOf(noWebmail));
  }

  /**
   * The per-preset property wins over the global one, which wins over the webmail; a
   * configured root keeps its own path, {@code /api} when it has none.
   */
  @Test
  public void testTheProperties() {
    EmailConnector connector = connector("https://webmail.example.com");
    System.setProperty(BlueMindEndpoint.CORE_URL_PROPERTY, "https://core.example.com/");
    assertEquals("https://core.example.com/api", BlueMindEndpoint.apiRootOf(connector));
    System.setProperty(BlueMindEndpoint.CORE_URL_PROPERTY + "." + ID, "https://bm.example.com/bm/api/");
    assertEquals("https://bm.example.com/bm/api", BlueMindEndpoint.apiRootOf(connector));
  }

  /**
   * Plain http is refused except on a loopback host, the password travelling in the
   * login's body.
   */
  @Test
  public void testHttpOnlyOnLoopback() {
    assertThrows(IllegalStateException.class, () -> BlueMindEndpoint.apiRootOf(connector("http://webmail.example.com")));
    assertThrows(IllegalStateException.class, () -> BlueMindEndpoint.apiRootOf(connector("ftp://webmail.example.com")));
    assertThrows(IllegalStateException.class, () -> BlueMindEndpoint.apiRootOf(connector("http://127.0.0.1.example.com")));
    assertThrows(IllegalStateException.class, () -> BlueMindEndpoint.apiRootOf(connector("http://cafe")));
    assertEquals("http://[::1]:8080/api", BlueMindEndpoint.apiRootOf(connector("http://[::1]:8080")));
    assertEquals("http://127.0.0.1:8080/api", BlueMindEndpoint.apiRootOf(connector("http://127.0.0.1:8080")));
    assertEquals("http://localhost/api", BlueMindEndpoint.apiRootOf(connector("http://localhost")));
  }

  /**
   * A preset.
   *
   * @param webmailUrl its webmail address
   * @return the preset
   */
  private static EmailConnector connector(String webmailUrl) {
    EmailConnector connector = new EmailConnector();
    connector.setId(ID);
    connector.setWebmailUrl(webmailUrl);
    return connector;
  }
}
