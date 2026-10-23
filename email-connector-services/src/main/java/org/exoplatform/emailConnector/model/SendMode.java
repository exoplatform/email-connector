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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * Whether, and how, a delegate may write mail in the owner's name (EXO-90582, phase 3):
 * the owner's consent on one share, and the shapes a connector's mail server accepts
 * from a delegate.
 * <ul>
 * <li>{@link #ON_BEHALF}: {@code From:} the owner, {@code Sender:} the delegate -- the
 * transparent shape, which a mail application that displays the sender shows as "the
 * delegate on behalf of the owner".</li>
 * <li>{@link #AS}: {@code From:} the owner and nothing else -- the mail reads as the
 * owner's own.</li>
 * <li>{@link #NONE}: no consent. The interface's and the REST body's value; a row stores
 * it as null ({@link #stored()}), which is also what every row written before this
 * feature reads.</li>
 * </ul>
 * What a connector's server accepts is the administrator's declaration, read from
 * properties at use time, as {@code email.connector.aclEngine[.<id>]} is: no mail server
 * can be asked whether it will accept a sender without sending, and a submission server
 * decides from the envelope alone -- Stalwart refuses another's address at MAIL unless
 * its administrator allows the pair, a default Postfix accepts any (EXO-90586).
 * {@link #declaredFor(Long)} is the one reading of it, which the capability probe, the
 * owner's write and the delegate's usable list share.
 */
public enum SendMode {

  /** No consent: the delegate writes in their own name only. */
  NONE,

  /** {@code From:} the owner, {@code Sender:} the delegate. */
  ON_BEHALF,

  /** {@code From:} the owner, nothing naming the delegate. */
  AS;

  /**
   * The shapes the SMTP server of every connector accepts from a delegate, unless a
   * connector's own property says otherwise: {@code none}, {@code onBehalf} or
   * {@code as} -- cumulative, {@code as} includes {@code onBehalf}.
   */
  public static final String   MODES_PROPERTY        = "email.connector.sendIdentity.modes";

  /** The prefix of one connector's own declaration: {@code …modes.<connectorId>}. */
  public static final String   MODES_PROPERTY_PREFIX = MODES_PROPERTY + ".";

  /**
   * The kill switch: {@code false} declares nothing on any connector, which hides every
   * control and refuses every consent but its withdrawal.
   */
  public static final String   ENABLED_PROPERTY      = "email.connector.sendIdentity.enabled";

  /** The declaration when none is made: on behalf, which needs no server change. */
  public static final String   DEFAULT_MODES         = "onBehalf";

  /** The log of the declaration's reading. */
  private static final Log     LOG                   = ExoLogger.getLogger(SendMode.class);

  /** The declarations already said unreadable, so each is logged once per JVM. */
  private static final Set<String> WARNED            = ConcurrentHashMap.newKeySet();

  /**
   * Whether this consent covers a requested shape: {@code AS} covers both -- the more
   * transparent shape is always available to whoever may sign as the owner --
   * {@code ON_BEHALF} covers itself, {@code NONE} nothing.
   *
   * @param requested the shape a mail would go out under
   * @return true when this consent allows it
   */
  public boolean allows(SendMode requested) {
    if (requested == null || requested == NONE || this == NONE) {
      return false;
    }
    return this == AS || requested == ON_BEHALF;
  }

  /**
   * The stored form: the name, null for {@link #NONE}.
   *
   * @return the column value
   */
  public String stored() {
    return this == NONE ? null : name();
  }

  /**
   * A stored value read back: null, blank or unknown reads as no consent (null), never
   * as a consent this version does not know.
   *
   * @param stored the column value
   * @return {@link #ON_BEHALF}, {@link #AS} or null
   */
  public static SendMode fromStored(String stored) {
    SendMode mode = of(stored);
    return mode == NONE ? null : mode;
  }

  /**
   * A mode by name, leniently: case and surrounding blanks ignored.
   *
   * @param name the name, as the interface and the REST body send it
   * @return the mode, or null for a blank or unknown name
   */
  public static SendMode of(String name) {
    if (StringUtils.isBlank(name)) {
      return null;
    }
    try {
      return valueOf(name.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Whether the administrator left writing in another's name switched on.
   *
   * @return false only when {@link #ENABLED_PROPERTY} says {@code false}
   */
  public static boolean isEnabled() {
    String value = System.getProperty(ENABLED_PROPERTY);
    return StringUtils.isBlank(value) || !"false".equalsIgnoreCase(value.trim());
  }

  /**
   * The shapes a connector's SMTP server accepts from a delegate, as the administrator
   * declared them: the connector's own property, else the global one, else
   * {@value #DEFAULT_MODES}. {@code as} yields both shapes, {@code onBehalf} on behalf
   * only, {@code none} nothing; a comma list is the union of its words. A word this
   * version cannot read declares nothing (logged once): an unreadable declaration never
   * widens what a delegate may do. Empty whenever the kill switch is off.
   *
   * @param connectorId the connector, null for the global declaration only
   * @return the declared shapes, never containing {@link #NONE}, never null
   */
  public static Set<SendMode> declaredFor(Long connectorId) {
    if (!isEnabled()) {
      return Collections.emptySet();
    }
    String value = connectorId == null ? null : System.getProperty(MODES_PROPERTY_PREFIX + connectorId);
    if (StringUtils.isBlank(value)) {
      value = System.getProperty(MODES_PROPERTY);
    }
    if (StringUtils.isBlank(value)) {
      value = DEFAULT_MODES;
    }
    Set<SendMode> declared = EnumSet.noneOf(SendMode.class);
    for (String word : value.split(",")) {
      String mode = word.trim().toLowerCase(Locale.ROOT);
      switch (mode) {
      case "as" -> {
        declared.add(ON_BEHALF);
        declared.add(AS);
      }
      case "onbehalf" -> declared.add(ON_BEHALF);
      case "none", "" -> {
        // Declares nothing.
      }
      default -> {
        if (WARNED.add(value)) {
          LOG.warn("Unreadable send identity declaration '{}' for connector {}: expected none, onBehalf or as; '{}' declares nothing",
                   value,
                   connectorId,
                   word.trim());
        }
      }
      }
    }
    return Collections.unmodifiableSet(declared);
  }

  /**
   * The shapes a delegate can write in now: the owner's consent, narrowed to what the
   * connector declares, less the shapes the owner's mail server refused since the owner
   * last set it ({@link #refusedByServer}). Never wider than the consent: an
   * {@code ON_BEHALF} consent on a connector declaring {@code as} stays on behalf. The one
   * formula the delegate's list and the send guard share.
   *
   * @param granted the owner's consent, null for none
   * @param refusedDate when the server last refused one, null for never
   * @param refusedMode the shape it refused, null when the refusal named none
   * @param declared the connector's declared shapes
   * @return the usable shapes, most transparent first, possibly empty
   */
  public static List<SendMode> usable(SendMode granted, Date refusedDate, SendMode refusedMode, Set<SendMode> declared) {
    List<SendMode> usable = new ArrayList<>();
    if (granted == null || granted == NONE || declared == null) {
      return usable;
    }
    for (SendMode mode : List.of(ON_BEHALF, AS)) {
      if (granted.allows(mode) && declared.contains(mode) && !refusedByServer(mode, refusedDate, refusedMode)) {
        usable.add(mode);
      }
    }
    return usable;
  }

  /**
   * Whether the owner's mail server's last refusal blocks a shape (EXO-90626): a refusal
   * as the owner blocks writing as her only, since a server that refuses the owner's
   * address from the delegate may well accept it beside the delegate's own; a refusal on
   * her behalf blocks both, the scale the consent itself follows ({@link #allows}). A
   * refusal that named no shape -- recorded before the shape was -- blocks both, as every
   * refusal did then: an unknown reading never widens what a delegate may do.
   *
   * @param requested the shape a mail would go out under
   * @param refusedDate when the server last refused one, null for never
   * @param refusedMode the shape it refused, null when the refusal named none
   * @return true when that shape may not be used until the owner sets the consent again
   */
  public static boolean refusedByServer(SendMode requested, Date refusedDate, SendMode refusedMode) {
    if (refusedDate == null) {
      return false;
    }
    return refusedMode != AS || requested == AS;
  }
}
