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
package org.exoplatform.emailConnector.senderlogo;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.regex.Pattern;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.utils.SenderLogoUtils;

/**
 * Reads TXT records through the JDK's own DNS client (JNDI's DNS provider, which uses
 * the host's resolvers), for the sender logo's BIMI and DMARC lookups (EXO-90893).
 * <p>
 * Bounded: two seconds for the first try, one retry, at most {@link #MAX_RECORDS}
 * records read. Only a plain DNS name is ever asked: JNDI picks a provider from a
 * name's URL scheme, so a name holding a {@code :} could reach another provider, and
 * none can get here.
 */
@Component
public class JndiDnsTxtLookup implements DnsTxtLookup {

  /** The most TXT records read for one name: a real name carries a handful. */
  static final int             MAX_RECORDS     = 20;

  /** The first try's timeout, in ms; each retry doubles it. */
  private static final String  INITIAL_TIMEOUT = "2000";

  /** How many times a query is retried. */
  private static final String  RETRIES         = "1";

  /** The only names asked: letters, digits, hyphens, underscores and dots. */
  private static final Pattern DNS_NAME        = Pattern.compile("[a-z0-9_.-]{1,253}");

  /**
   * Reads the TXT records of a name.
   *
   * @param name the DNS name, lower-cased
   * @return the records, empty when the name does not exist or has none
   * @throws DnsLookupException when the DNS could not answer
   */
  @Override
  public List<String> lookup(String name) throws DnsLookupException {
    if (name == null || !DNS_NAME.matcher(name).matches()) {
      return List.of();
    }
    Hashtable<String, String> environment = new Hashtable<>(); // NOSONAR the JNDI API takes a Hashtable
    environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
    environment.put(Context.PROVIDER_URL, "dns:");
    environment.put("com.sun.jndi.dns.timeout.initial", INITIAL_TIMEOUT);
    environment.put("com.sun.jndi.dns.timeout.retries", RETRIES);
    DirContext context = null;
    try {
      context = new InitialDirContext(environment);
      Attributes attributes = context.getAttributes(name, new String[] { "TXT" });
      Attribute txt = attributes == null ? null : attributes.get("TXT");
      List<String> records = new ArrayList<>();
      if (txt != null) {
        NamingEnumeration<?> values = txt.getAll();
        while (values.hasMore() && records.size() < MAX_RECORDS) {
          records.add(SenderLogoUtils.txtValue(String.valueOf(values.next())));
        }
      }
      return records;
    } catch (NameNotFoundException e) {
      return List.of();
    } catch (NamingException e) {
      throw new DnsLookupException(e);
    } finally {
      close(context);
    }
  }

  /**
   * Closes a JNDI context, quietly.
   *
   * @param context the context, or null
   */
  private static void close(DirContext context) {
    if (context != null) {
      try {
        context.close();
      } catch (NamingException e) {
        // Nothing is held by a DNS context once its query answered.
      }
    }
  }
}
