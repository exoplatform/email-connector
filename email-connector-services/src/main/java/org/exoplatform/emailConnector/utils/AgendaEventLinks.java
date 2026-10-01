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
package org.exoplatform.emailConnector.utils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * Tells an event this very deployment's Agenda mailed (EXO-90840) from any other
 * invitation: the agenda add-on attaches every notified event as {@code event.ics}, with
 * the UID {@code agenda-event-<id>@<host>} ({@code Utils.icsUid}) and a {@code URL} back
 * to the event in this portal, {@code <base>/portal/<site>/agenda?eventId=<id>}
 * ({@code EventIcsBuilder.eventUrl}). Such an event is answered in Agenda, never by a
 * REPLY mail.
 * <p>
 * Both must hold, and say the same thing: the UID's prefix and event id, the URL's shape
 * and event id, and the URL's authority -- host and port -- equal to this deployment's
 * configured domain, the one Agenda builds the link from (the rule, and the address
 * shape, of caldav-integration's {@code CaldavInboundService.EXO_EVENT_LINK} and
 * {@code deploymentNamedBy}, copied here: email-connector does not depend on it). The UID
 * names the host without its port, as Agenda writes it. A UID of Agenda's shape with a
 * URL on another host -- another eXo's event, or a forgery -- is not recognised, and is
 * an ordinary invitation.
 * <p>
 * The link handed to the reader is rebuilt from this deployment's own domain and the
 * validated site name and event id, never copied from the sender's text.
 */
public final class AgendaEventLinks {

  /** The prefix of the UID Agenda gives an event it mails. */
  static final String          UID_PREFIX = "agenda-event-";

  /** Agenda's UID: its prefix, the event id, the host. */
  private static final Pattern AGENDA_UID = Pattern.compile("^agenda-event-(\\d+)@([^\\s@]+)$", Pattern.CASE_INSENSITIVE);

  /**
   * Agenda's event link, the whole value: an http(s) scheme -- the reader turns it into a
   * link -- then caldav-integration's authority and path shape, the site name narrowed to
   * the characters a portal site name is made of.
   */
  private static final Pattern AGENDA_LINK =
                                           Pattern.compile("^https?://([^/\\s<>\"']+)/portal/([A-Za-z0-9_.-]+)/agenda\\?eventId=(\\d+)$",
                                                           Pattern.CASE_INSENSITIVE);

  /**
   * Not instantiable: static helpers only.
   */
  private AgendaEventLinks() {
  }

  /**
   * The Agenda link of an event this deployment mailed.
   *
   * @param uid the event's UID
   * @param url the event's URL property
   * @param ownDomain this deployment's configured domain, {@code CommonsUtils.getCurrentDomain()}
   * @return the link to the event in this portal's Agenda, rebuilt from {@code ownDomain};
   *         null when the event is not one of this deployment's Agenda
   */
  public static String localAgendaLink(String uid, String url, String ownDomain) {
    String own = authorityOf(ownDomain);
    if (own == null || StringUtils.isBlank(uid) || StringUtils.isBlank(url)) {
      return null;
    }
    Matcher uidMatch = AGENDA_UID.matcher(uid.trim());
    Matcher link = AGENDA_LINK.matcher(url.trim());
    if (!uidMatch.matches() || !link.matches()) {
      return null;
    }
    String ownHost = StringUtils.substringBefore(own, ":");
    if (!own.equals(link.group(1).toLowerCase(Locale.ROOT))
        || !ownHost.equals(uidMatch.group(2).toLowerCase(Locale.ROOT))
        || !uidMatch.group(1).equals(link.group(3))) {
      return null;
    }
    return StringUtils.removeEnd(ownDomain.trim(), "/") + "/portal/" + link.group(2) + "/agenda?eventId=" + link.group(3);
  }

  /**
   * The authority -- host and port -- of an address, with or without a scheme
   * (caldav-integration's {@code CaldavInboundService.authorityOf}).
   *
   * @param address the address
   * @return the authority, lower-cased, or null when there is none
   */
  static String authorityOf(String address) {
    if (StringUtils.isBlank(address)) {
      return null;
    }
    String rest = address.trim();
    int scheme = rest.indexOf("://");
    if (scheme >= 0) {
      rest = rest.substring(scheme + 3);
    }
    int slash = rest.indexOf('/');
    if (slash >= 0) {
      rest = rest.substring(0, slash);
    }
    return StringUtils.isBlank(rest) ? null : rest.toLowerCase(Locale.ROOT);
  }
}
