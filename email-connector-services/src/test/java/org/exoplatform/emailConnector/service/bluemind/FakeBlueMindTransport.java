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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.exoplatform.emailConnector.service.bluemind.BlueMindTransportException.Kind;

/**
 * An in-memory BlueMind behind the port, for the engines' tests: one account whose login
 * answer and {@code _vacation} are recorded values, a forward it never lets anyone
 * change, and a log of every call.
 * <p>
 * The values come from what is known of BlueMind: the login answer's uids and zone are
 * those of the login <b>captured</b> live on 2026-09-16 on the BlueMind test account (by
 * the CalDAV add-on); the reply set in the webmail is <b>derived</b> from the core API
 * javadoc 5.0.7563 ({@code MailFilter.Vacation}), with instants in epoch milliseconds --
 * to verify live (the vacation plan's V0 list of BlueMind checks).
 * <p>
 * The port has no write of {@code _filter} or {@code _forwarding}: this fake keeps the
 * forward the account holds and a test asserts it is the same object at the end, and
 * that the call log names only the port's own calls.
 */
public final class FakeBlueMindTransport implements BlueMindMailboxTransport {

  /** The mailbox uid of the account, from the captured login answer. */
  public static final String       USER_UID    = "751E6D1A-7FDB-49B2-B668-B569E9A5A42D";

  /** The domain uid of the account, from the captured login answer. */
  public static final String       DOMAIN_UID  = "19d43481671.internal";

  /** The account's own zone, from the captured login answer's {@code authUser.settings}. */
  public static final String       ACCOUNT_ZONE = "Europe/Paris";

  /** The session key the login answers. */
  public static final String       API_KEY     = "test-session-key";

  /**
   * A reply set in the webmail (derived): on from 2026-10-01 10:37 to 2026-10-15 18:00
   * Europe/Paris, CRLF text and an HTML body.
   */
  public static final BlueMindVacation WEBMAIL = new BlueMindVacation(true,
                                                                      1790843820000L,
                                                                      1792080000000L,
                                                                      "Absent",
                                                                      "Je suis absente.\r\nRetour le 16.",
                                                                      "<p>Je suis absente.</p>");

  /** A mailbox where no reply was ever set. */
  public static final BlueMindVacation EMPTY   = new BlueMindVacation(false, null, null, null, null, null);

  /** The forward the account holds; nothing may change it. */
  public static final BlueMindForwarding FORWARD = new BlueMindForwarding(true, true, Set.of("anais.backup@demo3.livecollab.fr"));

  /**
   * One call on the port.
   *
   * @param name the method
   * @param apiRoot the core API root, for a login
   * @param login the login, for a login
   * @param session the session the call carried, for the other calls
   */
  public record Call(String name, String apiRoot, String login, BlueMindSession session) {
  }

  private final List<Call>                          calls    = Collections.synchronizedList(new ArrayList<>());

  private final Map<String, BlueMindTransportException> failures = new ConcurrentHashMap<>();

  private final List<BlueMindVacation>              posted   = Collections.synchronizedList(new ArrayList<>());

  private volatile BlueMindVacation                  vacation = EMPTY;

  private volatile BlueMindSession                   answer;

  private volatile BlueMindForwarding                forwarding = FORWARD;

  /**
   * Makes one call fail, every time, until cleared.
   *
   * @param method {@code login}, {@code getVacation}, {@code setVacation} or
   *          {@code getForwarding}
   * @param kind the failure, or null to clear it
   */
  public void fail(String method, Kind kind) {
    if (kind == null) {
      failures.remove(method);
    } else {
      failures.put(method, new BlueMindTransportException(kind, "fake " + method + " " + kind));
    }
  }

  /**
   * Replaces the login answer's account; null restores the captured one.
   *
   * @param userUid the uid
   * @param domainUid the domain uid
   * @param zone the account's zone
   */
  public void answerLoginAs(String userUid, String domainUid, String zone) {
    this.answer = new BlueMindSession(null, userUid, domainUid, API_KEY, zone);
  }

  /**
   * Replaces the reply the account holds.
   *
   * @param newVacation the reply
   */
  public void setHeld(BlueMindVacation newVacation) {
    this.vacation = newVacation;
  }

  /**
   * The reply the account holds.
   *
   * @return the reply
   */
  public BlueMindVacation held() {
    return vacation;
  }

  /**
   * The forward the account holds.
   *
   * @return the forward
   */
  public BlueMindForwarding forwarding() {
    return forwarding;
  }

  /**
   * Every reply written, in order.
   *
   * @return a copy
   */
  public List<BlueMindVacation> posted() {
    synchronized (posted) {
      return new ArrayList<>(posted);
    }
  }

  /**
   * Every call, in order.
   *
   * @return a copy
   */
  public List<Call> calls() {
    synchronized (calls) {
      return new ArrayList<>(calls);
    }
  }

  /**
   * The calls of one method.
   *
   * @param name the method
   * @return the calls
   */
  public List<Call> calls(String name) {
    return calls().stream().filter(call -> call.name().equals(name)).toList();
  }

  /**
   * Logs in: the captured account, unless a test changed it.
   *
   * @param apiRoot the core API root
   * @param login the login
   * @param password the password
   * @return the session
   * @throws BlueMindTransportException when a test made the login fail
   */
  @Override
  public BlueMindSession login(String apiRoot, String login, String password) throws BlueMindTransportException {
    calls.add(new Call("login", apiRoot, login, null));
    throwIfFailing("login");
    BlueMindSession account = answer;
    return account == null ? new BlueMindSession(apiRoot, USER_UID, DOMAIN_UID, API_KEY, ACCOUNT_ZONE)
                           : new BlueMindSession(apiRoot, account.userUid(), account.domainUid(), API_KEY, account.timeZone());
  }

  /**
   * Records the logout.
   *
   * @param session the session
   */
  @Override
  public void logout(BlueMindSession session) {
    calls.add(new Call("logout", null, null, session));
  }

  /**
   * The reply held.
   *
   * @param session the session
   * @return the reply
   * @throws BlueMindTransportException when a test made it fail
   */
  @Override
  public BlueMindVacation getVacation(BlueMindSession session) throws BlueMindTransportException {
    calls.add(new Call("getVacation", null, null, session));
    throwIfFailing("getVacation");
    return vacation;
  }

  /**
   * Replaces the reply held.
   *
   * @param session the session
   * @param newVacation the reply
   * @throws BlueMindTransportException when a test made it fail
   */
  @Override
  public void setVacation(BlueMindSession session, BlueMindVacation newVacation) throws BlueMindTransportException {
    calls.add(new Call("setVacation", null, null, session));
    throwIfFailing("setVacation");
    posted.add(newVacation);
    vacation = newVacation;
  }

  /**
   * The forward held.
   *
   * @param session the session
   * @return the forward
   * @throws BlueMindTransportException when a test made it fail
   */
  @Override
  public BlueMindForwarding getForwarding(BlueMindSession session) throws BlueMindTransportException {
    calls.add(new Call("getForwarding", null, null, session));
    throwIfFailing("getForwarding");
    return forwarding;
  }

  /**
   * Throws the failure a test set for a method.
   *
   * @param method the method
   * @throws BlueMindTransportException when one is set
   */
  private void throwIfFailing(String method) throws BlueMindTransportException {
    BlueMindTransportException failure = failures.get(method);
    if (failure != null) {
      throw failure;
    }
  }
}
