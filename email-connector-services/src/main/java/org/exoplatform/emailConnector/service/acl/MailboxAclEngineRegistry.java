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
package org.exoplatform.emailConnector.service.acl;

import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Which {@link MailboxAclEngine} a connector preset uses. Every engine bean is collected
 * (the service-plugin mechanism, by type), and the preset's choice is read from a
 * property: {@code email.connector.aclEngine.<connectorId>} for one preset,
 * {@code email.connector.aclEngine} for all, {@code imap} when neither is set -- and
 * since the IMAP engine probes by attempting MYRIGHTS before doing anything, a default
 * of {@code imap} on a server without ACLs degrades to "unsupported", never to a
 * failed SETACL. A BlueMind preset must be configured {@code bluemind} once that
 * engine exists: IMAP writes never reach its mailstore (see {@code package-info}).
 * <p>
 * TODO: the delegation plan (section 3.2) wanted this choice in
 * {@code EmailConnector.providerConfig["aclEngine"]}. That map is inbound-only in the
 * code -- it carries what an administrator typed, is validated against the credentials
 * provider's own descriptor and is never filled on the way out -- so a key of this
 * add-on's cannot ride it without a change to the provider descriptors in
 * {@code commons-exo}. The property is the selector until that lands.
 */
@Service
public class MailboxAclEngineRegistry {

  private static final Log        LOG                    = ExoLogger.getLogger(MailboxAclEngineRegistry.class);

  /** The property naming the engine of every preset, unless overridden per preset. */
  public static final String      ENGINE_PROPERTY        = "email.connector.aclEngine";

  /** The prefix of the per-preset property: {@code email.connector.aclEngine.<id>}. */
  public static final String      ENGINE_PROPERTY_PREFIX = ENGINE_PROPERTY + ".";

  @Autowired
  private List<MailboxAclEngine>  engines;

  /**
   * The engine of a preset.
   *
   * @param connector the connector preset
   * @return the engine, the no-op one when the configured name matches no bean
   */
  public MailboxAclEngine engineFor(EmailConnector connector) {
    String name = engineName(connector);
    for (MailboxAclEngine engine : engines) {
      if (name.equalsIgnoreCase(engine.getName())) {
        return engine;
      }
    }
    LOG.warn("No mailbox ACL engine named '{}' for connector {}; sharing is disabled on it", name, connectorId(connector));
    for (MailboxAclEngine engine : engines) {
      if (NoopAclEngine.NAME.equalsIgnoreCase(engine.getName())) {
        return engine;
      }
    }
    return new NoopAclEngine();
  }

  /**
   * The configured engine name of a preset: its own property, else the global one, else
   * {@code imap}.
   *
   * @param connector the connector preset
   * @return the name, lower-case, never blank
   */
  String engineName(EmailConnector connector) {
    String name = null;
    Long id = connectorId(connector);
    if (id != null) {
      name = System.getProperty(ENGINE_PROPERTY_PREFIX + id);
    }
    if (StringUtils.isBlank(name)) {
      name = System.getProperty(ENGINE_PROPERTY);
    }
    return StringUtils.isBlank(name) ? ImapAclEngine.NAME : name.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * @param connector the connector preset, possibly null
   * @return its id, or null
   */
  private Long connectorId(EmailConnector connector) {
    return connector == null ? null : connector.getId();
  }
}
