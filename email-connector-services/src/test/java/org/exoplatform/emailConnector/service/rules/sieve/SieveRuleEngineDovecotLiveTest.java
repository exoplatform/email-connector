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
package org.exoplatform.emailConnector.service.rules.sieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.mail.Address;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.HopRef;
import org.exoplatform.emailConnector.model.ReconcileReport;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRule.Action;
import org.exoplatform.emailConnector.model.ServerRule.Condition;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerRuleSet;
import org.exoplatform.emailConnector.model.ServerRulesState;
import org.exoplatform.emailConnector.model.VacationSetting;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;

/**
 * {@link SieveRuleEngine} against a <b>real Dovecot 2.3.21 / Pigeonhole 0.5.21</b> -- the
 * certification of the server rules on Dovecot (slice S5, EXO-90653), not part of the
 * unit suite. It runs only with {@code -Ddovecot.it=true}, against the ManageSieve-enabled
 * copy of the Dovecot rig ({@code ~/eXo/v0-dovecot-sieve}: containers {@code v0sieve-*},
 * ManageSieve {@code 127.0.0.1:14190}, IMAPS {@code 12993}, submission {@code 12587}),
 * users {@code alice@dovecot.local} / {@code bob@dovecot.local}:
 *
 * <pre>
 * docker exec v0sieve-dovecot cat /etc/dovecot/cert.pem &gt; /tmp/v0sieve-dovecot.pem
 * docker exec v0sieve-postfix cat /etc/ssl/certs/ssl-cert-snakeoil.pem &gt; /tmp/v0sieve-postfix.pem
 * ALICE_PASSWORD=… BOB_PASSWORD=… mvn -o test -pl email-connector-services -Ddovecot.it=true \
 *   -Ddovecot.cert=/tmp/v0sieve-dovecot.pem,/tmp/v0sieve-postfix.pem -Dtest=SieveRuleEngineDovecotLiveTest
 * </pre>
 *
 * Passwords are read from the environment only. TLS is verified for real: the rig's two
 * certificates (Dovecot's, from the pinned image, and Postfix's snakeoil one, both
 * {@code CN=localhost}) are the only trust anchors, and the host name is checked, so every connection goes to {@code localhost}. Ports:
 * {@code -Ddovecot.sieve.port}, {@code -Ddovecot.imap.port}, {@code -Ddovecot.smtp.port}.
 * <p>
 * What it certifies, through the engine and at LMTP delivery (bob sends, the test reads
 * alice's folders and bob's INBOX): the rules published next to an automatic reply, the
 * script read back byte for byte, the {@code include} wrapper around a script another
 * client activated (policy case 3), the vacation-token refusal and the wrapper ordering
 * (policy rules 5 and 6), the reconciliation of the hops, and a rule whose folder was
 * deleted elsewhere no longer breaking the run. Each test starts and ends with no script,
 * no test mail and no test folder on either account.
 * <p>
 * <b>What a Dovecot deployment needs for the Sieve engine</b> (observed on this rig):
 * <ul>
 * <li>ManageSieve on: {@code protocols = imap lmtp sieve} and a
 * {@code service managesieve-login { inet_listener sieve { port = 4190 } }} reachable from
 * eXo, with TLS ({@code ssl = yes}): eXo only talks to it after {@code STARTTLS}, then
 * SASL {@code PLAIN}.</li>
 * <li>Sieve at delivery: {@code protocol lmtp { mail_plugins = $mail_plugins sieve }} (or
 * {@code protocol lda} for dovecot-lda), and
 * {@code plugin { sieve = file:~/sieve;active=~/.dovecot.sieve }}. Without the plugin
 * ManageSieve still stores scripts, and nothing runs them.</li>
 * <li>The extensions eXo reads from the {@code SIEVE} line: {@code fileinto},
 * {@code imap4flags}, {@code include} (else a user with another client's script is
 * refused, policy case 4), {@code vacation}, {@code date}, {@code relational},
 * {@code encoded-character}, and {@code mailbox} -- with it every {@code fileinto} is
 * guarded by {@code mailboxexists}, without it a folder deleted in another client fails
 * the whole run (no filing, no hop keyword, <b>no automatic reply</b>). All are in
 * Pigeonhole's default set; a {@code sieve_extensions} line must not remove them.</li>
 * <li>Logins equal to email addresses: ManageSieve authenticates with the IMAP channel's
 * material, so the passdb must accept the same login for IMAP and ManageSieve.</li>
 * <li>No {@code INDEXPVT} on a shared namespace: {@code \Seen} and keywords live in the
 * owner's index, so a hop keyword set at delivery is the one eXo's sync reads.</li>
 * <li>{@code PERMANENTFLAGS} with {@code \*} (Dovecot's default): the hop keyword is an
 * arbitrary IMAP keyword.</li>
 * </ul>
 * Also observed, and relied on by the policy rather than by the server: Pigeonhole accepts
 * a wrapper whose included script does not exist ({@code OK (WARNINGS)}), and a wrapper
 * that runs {@code vacation} twice passes {@code CHECKSCRIPT}; both fail only at delivery
 * (a compile failure, and "duplicate vacation action not allowed"), where the mail is kept
 * in the INBOX and nothing else runs.
 */
@EnabledIfSystemProperty(named = "dovecot.it", matches = "true")
class SieveRuleEngineDovecotLiveTest {

  private static final String ALICE        = "alice@dovecot.local";

  private static final String BOB          = "bob@dovecot.local";

  private static final String HOST         = "localhost";

  private static final long   CONNECTOR_ID = 90653L;

  /** The root of every folder the test creates. */
  private static final String ROOT         = "S5IT";

  private static final String EXO_FOLDER   = ROOT + "/Factures \u00e9";

  private static final String THEIRS       = ROOT + "/Theirs";

  private static final String GONE         = ROOT + "/Gone";

  private static final String FOREIGN      = "roundcube";

  private static final String FOREIGN_VAC  = "roundcube-vac";

  /** Every script name the test may leave on alice's account. */
  private static final List<String> SCRIPTS = List.of(ExoSieveScript.WRAPPER_NAME, ExoSieveScript.SCRIPT_NAME, FOREIGN, FOREIGN_VAC);

  /** How long a delivery or an automatic reply is waited for. */
  private static final long   WAIT_MILLIS  = 30_000;

  private static SSLSocketFactory tls;

  private SieveRuleEngine     engine;

  private MailboxAclSession   alice;

  /** A tag unique to the test, in every subject it sends. */
  private String              tag;

  /**
   * Builds the TLS layer that trusts the rig's certificates only.
   *
   * @throws Exception when the certificate cannot be read
   */
  @BeforeAll
  static void trustTheRig() throws Exception {
    String path = System.getProperty("dovecot.cert");
    assertNotNull(path, "-Ddovecot.cert=<dovecot.pem>,<postfix.pem> is required (see the class Javadoc)");
    tls = socketFactory(path.split(","));
  }

  /**
   * Points the engine at the rig and resets alice's scripts, folders and the test mails.
   *
   * @throws Exception when the rig cannot be reached
   */
  @BeforeEach
  void setUp() throws Exception {
    System.setProperty(ManageSieveEndpoint.HOST_PROPERTY + "." + CONNECTOR_ID, HOST);
    System.setProperty(ManageSieveEndpoint.PORT_PROPERTY + "." + CONNECTOR_ID, System.getProperty("dovecot.sieve.port", "14190"));
    ManageSieveConnector connector = new ManageSieveConnector(mock(EmailCredentialsResolver.class));
    connector.configure(tls, 15_000, 30_000, 60_000);
    engine = new SieveRuleEngine(connector, new SieveScriptPolicy());
    EmailConnector preset = new EmailConnector();
    preset.setId(CONNECTOR_ID);
    preset.setAuthProviderName("personal");
    alice = new MailboxAclSession(preset, "alice", ALICE, null, null, () -> new PasswordAuthentication(ALICE, password("ALICE_PASSWORD")));
    tag = "S5IT" + Long.toString(System.currentTimeMillis(), 36);
    tearDown();
  }

  /**
   * Leaves alice with no script, no test folder, and both INBOXes without test mail.
   *
   * @throws Exception when the rig cannot be reached
   */
  @AfterEach
  void tearDown() throws Exception {
    ManageSieveClient client = raw();
    try {
      client.setActive("");
      List<String> present = client.listScripts().stream().map(SieveScriptInfo::name).toList();
      for (String name : SCRIPTS) {
        if (present.contains(name)) {
          client.deleteScript(name);
        }
      }
    } finally {
      client.logout();
    }
    try (Store store = store(ALICE, "ALICE_PASSWORD")) {
      purge(store, "INBOX");
      Folder root = store.getFolder(ROOT);
      if (root.exists()) {
        for (Folder child : root.list()) {
          child.delete(true);
        }
        root.delete(true);
      }
    }
    try (Store store = store(BOB, "BOB_PASSWORD")) {
      purge(store, "INBOX");
    }
  }

  /**
   * The rig offers everything the rules need, and a fresh account has no script another
   * client activated.
   *
   * @throws Exception when the rig cannot be reached
   */
  @Test
  void probeOffersTheRulesAndNoConflict() throws Exception {
    ServerRuleCapabilities capabilities = engine.probe(alice);
    assertTrue(capabilities.supported());
    assertFalse(capabilities.publishConflict());
    ManageSieveClient client = raw();
    try {
      for (String extension : List.of("fileinto", "imap4flags", "include", "vacation", "date", "relational", "encoded-character", "mailbox")) {
        assertTrue(client.getCapabilities().hasExtension(extension), extension + " is advertised");
      }
    } finally {
      client.logout();
    }
  }

  /**
   * A rule saved next to an automatic reply: the script is read back byte for byte as eXo
   * generates it, and at delivery the mail is filed and starred while the reply still
   * goes out -- the vacation section runs before the rule's {@code stop} -- with the
   * backslash, the quote and the dollar of the reply decoded as typed.
   *
   * @throws Exception when the rig cannot be reached
   */
  @Test
  void rulesBesideAReplyAreStoredVerbatimAndRunAtDelivery() throws Exception {
    createFolders(EXO_FOLDER);
    engine.writeVacation(alice, reply("Away. Path C:\\temp, \"quoted\", $HOME and ${x}."), 7, null);
    ServerRuleSet saved = engine.saveRule(alice, fileRule(tag + "-INV", EXO_FOLDER, true), engine.listRules(alice).scriptHash());
    assertEquals(ServerRulesState.OWN, saved.state());
    assertEquals(1, saved.rules().size());

    String text = script(ExoSieveScript.SCRIPT_NAME);
    assertEquals(saved.scriptHash(), ExoSieveScript.sha256(text), "the hash eXo keeps is the hash of what the server returns");
    ExoSieveScript model = ExoSieveScript.parse(text).orElseThrow();
    assertEquals(text, generated(model), "GETSCRIPT is byte for byte what eXo generates for this server");
    assertTrue(text.contains("mailboxexists"), "mailbox is advertised: the fileinto is guarded");
    assertEquals(ExoSieveScript.SCRIPT_NAME, active());

    Message[] replies = sendAndAwaitReply(tag + "-INV invoice");
    Message filed = awaitIn(EXO_FOLDER, tag + "-INV");
    assertTrue(filed.isSet(Flags.Flag.FLAGGED), "STAR is \\Flagged");
    assertEquals(0, find("INBOX", tag + "-INV", false).size(), "filed, not kept");
    assertEquals(1, replies.length, "one automatic reply, although the rule stopped");
    assertTrue(bodyOf(replies[0]).contains("Path C:\\temp, \"quoted\", $HOME and ${x}."), "decoded as typed");
  }

  /**
   * Policy case 3: another client's script is active and {@code include} is advertised.
   * eXo writes its script and the wrapper, moves only the active pointer, and never
   * touches the other script. Rule 6: with a filing rule, the other script runs first (its
   * {@code stop} shadows eXo's rules); with the reply alone, eXo's script runs first (the
   * other script's {@code stop} no longer swallows the reply).
   *
   * @throws Exception when the rig cannot be reached
   */
  @Test
  void anotherClientsScriptIsWrappedAndOrdered() throws Exception {
    createFolders(EXO_FOLDER, THEIRS);
    String theirs = "require [\"fileinto\"];\r\nif header :contains \"subject\" \"" + tag + "-THEIRS\" {\r\n  fileinto \"" + THEIRS
        + "\";\r\n  stop;\r\n}\r\n";
    putForeign(FOREIGN, theirs);
    assertTrue(engine.probe(alice).publishConflict());

    ServerRuleSet saved = engine.saveRule(alice, fileRule(tag + "-ORDER", EXO_FOLDER, false), null);
    assertEquals(FOREIGN, saved.foreignScriptName());
    assertEquals(ExoSieveScript.WRAPPER_NAME, active());
    assertEquals(SieveScriptPolicy.wrapper(FOREIGN, false), script(ExoSieveScript.WRAPPER_NAME), "a filing rule: theirs first");
    assertEquals(theirs, script(FOREIGN), "the other client's script is never rewritten");

    send(tag + "-THEIRS " + tag + "-ORDER both");
    send(tag + "-ORDER only eXo");
    assertEquals(1, awaitAll(THEIRS, tag + "-THEIRS", 1).size());
    assertEquals(1, awaitAll(EXO_FOLDER, tag + "-ORDER only", 1).size());
    assertEquals(0, find(EXO_FOLDER, tag + "-THEIRS", false).size(), "their stop shadows eXo's rule: one copy");

    engine.deleteRule(alice, saved.rules().get(0).ref(), null);
    engine.writeVacation(alice, reply("Away (vacation only)."), 7, null);
    assertEquals(SieveScriptPolicy.wrapper(FOREIGN, true), script(ExoSieveScript.WRAPPER_NAME), "the reply alone: eXo first");
    Message[] replies = sendAndAwaitReply(tag + "-THEIRS stopped by them");
    assertEquals(1, replies.length, "their stop no longer swallows the reply");
    assertEquals(1, awaitAll(THEIRS, tag + "-THEIRS stopped", 1).size(), "and their rule still files it");
  }

  /**
   * Policy rule 5: a reply is refused next to another client's script that holds
   * {@code vacation} -- two replies in one run are a delivery-time error on Pigeonhole, and
   * {@code CHECKSCRIPT} does not see it -- and nothing is written. Rules without a reply
   * still wrap that script.
   *
   * @throws Exception when the rig cannot be reached
   */
  @Test
  void aReplyIsRefusedNextToAnotherClientsReply() throws Exception {
    String theirs = "# their own out-of-office\r\nrequire [\"vacation\"];\r\nvacation :days 1 :handle \"theirs\" :subject \"Away\" \"Their reply.\";\r\n";
    putForeign(FOREIGN_VAC, theirs);

    ServerRuleConflictException refused = assertThrows(ServerRuleConflictException.class,
                                                       () -> engine.writeVacation(alice, reply("Mine."), 7, null));
    assertEquals(ServerRuleConflictException.MANAGED_ELSEWHERE, refused.getMessage());
    assertEquals(FOREIGN_VAC, refused.getScriptName());
    assertEquals(FOREIGN_VAC, active(), "nothing was activated");
    assertFalse(names().contains(ExoSieveScript.SCRIPT_NAME), "nothing was written");

    createFolders(EXO_FOLDER);
    engine.saveRule(alice, fileRule(tag + "-ORDER", EXO_FOLDER, false), null);
    assertEquals(ExoSieveScript.WRAPPER_NAME, active(), "rules without a reply wrap their script");
    assertEquals(theirs, script(FOREIGN_VAC));
  }

  /**
   * Reconciliation writes the hops eXo's rules need, is a no-op when nothing differs, and
   * removes a hop no longer needed; at delivery a hop leaves the mail in the INBOX with
   * its keyword.
   *
   * @throws Exception when the rig cannot be reached
   */
  @Test
  void hopsAreReconciledAndTagAtDelivery() throws Exception {
    HopRef hop = new HopRef("hop-7",
                            "Invoices, then the assistant",
                            true,
                            List.of(new Condition(ServerRule.SUBJECT, ServerRule.CONTAINS, null, tag + "-HOP")),
                            ServerRule.TAG_PREFIX + "7",
                            false);
    ReconcileReport first = engine.reconcile(alice, List.of(hop), null);
    assertEquals(List.of("hop-7"), first.published());
    assertEquals(ExoSieveScript.SCRIPT_NAME, active());
    String written = script(ExoSieveScript.SCRIPT_NAME);

    ReconcileReport again = engine.reconcile(alice, List.of(hop), first.rules().scriptHash());
    assertTrue(again.published().isEmpty() && again.removed().isEmpty(), "nothing differs, nothing is written");
    assertEquals(written, script(ExoSieveScript.SCRIPT_NAME));

    send(tag + "-HOP tagged");
    Message tagged = awaitIn("INBOX", tag + "-HOP");
    assertTrue(List.of(tagged.getFlags().getUserFlags()).contains(ServerRule.TAG_PREFIX + "7"), "the hop keyword is set at delivery");

    ReconcileReport removed = engine.reconcile(alice, List.of(), again.rules().scriptHash());
    assertEquals(List.of("hop-7"), removed.removed());
    assertTrue(removed.rules().rules().isEmpty());
  }

  /**
   * A rule whose folder another client deleted no longer breaks the run: the
   * {@code mailboxexists} guard keeps the mail in the INBOX and the reply still goes out
   * (unguarded, Pigeonhole fails the whole run and sends no reply).
   *
   * @throws Exception when the rig cannot be reached
   */
  @Test
  void aDeletedFolderNoLongerStopsTheReply() throws Exception {
    createFolders(GONE);
    engine.writeVacation(alice, reply("Away (stale folder)."), 7, null);
    engine.saveRule(alice, fileRule(tag + "-GONE", GONE, false), null);
    try (Store store = store(ALICE, "ALICE_PASSWORD")) {
      store.getFolder(GONE).delete(true);
    }
    Message[] replies = sendAndAwaitReply(tag + "-GONE to a deleted folder");
    assertEquals(1, replies.length, "the reply still goes out");
    assertEquals(1, awaitAll("INBOX", tag + "-GONE", 1).size(), "the mail stays in the INBOX");
  }

  // ---------------------------------------------------------------------------------------

  /**
   * A reply enabled from today to tomorrow, in Paris.
   *
   * @param text the reply's text
   * @return the setting
   */
  private static VacationSetting reply(String text) {
    LocalDate today = LocalDate.now(ZoneId.of("Europe/Paris"));
    return new VacationSetting(true, today.toString(), today.plusDays(1).toString(), "Europe/Paris", "Out of office", text, 0, null);
  }

  /**
   * A new rule that files mails from bob whose subject holds a marker, and stars them.
   *
   * @param marker the subject marker
   * @param folder the folder, as the mirrored list names it
   * @param stop whether the rule stops
   * @return the rule
   */
  private static ServerRule fileRule(String marker, String folder, boolean stop) {
    return new ServerRule(null,
                          "File " + marker,
                          true,
                          true,
                          List.of(new Condition(ServerRule.FROM, ServerRule.EQUALS, null, BOB),
                                  new Condition(ServerRule.SUBJECT, ServerRule.CONTAINS, null, marker)),
                          List.of(new Action(ServerRule.MOVE_TO_FOLDER, "CUSTOM:1", folder, null), new Action(ServerRule.STAR, null, null, null)),
                          stop);
  }

  /**
   * A password from the environment.
   *
   * @param variable the variable
   * @return the password
   */
  private static String password(String variable) {
    String value = System.getenv(variable);
    assertNotNull(value, variable + " is required in the environment");
    return value;
  }

  /**
   * A ManageSieve conversation as alice, standing for "another client".
   *
   * @return the authenticated client
   * @throws Exception when the rig cannot be reached
   */
  private static ManageSieveClient raw() throws Exception {
    ManageSieveClient client = ManageSieveClient.connect(HOST,
                                                         Integer.parseInt(System.getProperty("dovecot.sieve.port", "14190")),
                                                         tls,
                                                         15_000,
                                                         30_000,
                                                         60_000);
    client.authenticatePlain(new PasswordAuthentication(ALICE, password("ALICE_PASSWORD")));
    return client;
  }

  /**
   * Stores and activates a script as another client would.
   *
   * @param name the script's name
   * @param text the script
   * @throws Exception when the rig refuses
   */
  private static void putForeign(String name, String text) throws Exception {
    ManageSieveClient client = raw();
    try {
      client.putScript(name, text);
      client.setActive(name);
    } finally {
      client.logout();
    }
  }

  /**
   * A script as the server returns it.
   *
   * @param name the script's name
   * @return its text
   * @throws Exception when the rig cannot be reached
   */
  private static String script(String name) throws Exception {
    ManageSieveClient client = raw();
    try {
      return client.getScript(name);
    } finally {
      client.logout();
    }
  }

  /**
   * The text eXo generates for a model on this server: its capabilities decide the string
   * encoding and the {@code mailboxexists} guard.
   *
   * @param model the model
   * @return the text
   * @throws Exception when the rig cannot be reached
   */
  private static String generated(ExoSieveScript model) throws Exception {
    ManageSieveClient client = raw();
    try {
      return model.toScript(client.getCapabilities());
    } finally {
      client.logout();
    }
  }

  /**
   * The script names on alice's account.
   *
   * @return the names
   * @throws Exception when the rig cannot be reached
   */
  private static List<String> names() throws Exception {
    ManageSieveClient client = raw();
    try {
      return client.listScripts().stream().map(SieveScriptInfo::name).toList();
    } finally {
      client.logout();
    }
  }

  /**
   * The active script's name.
   *
   * @return the name, or null
   * @throws Exception when the rig cannot be reached
   */
  private static String active() throws Exception {
    ManageSieveClient client = raw();
    try {
      return SieveScriptPolicy.activeScript(client.listScripts());
    } finally {
      client.logout();
    }
  }

  /**
   * The mail session: IMAPS and submission, TLS verified against the rig's certificate.
   *
   * @return the session
   */
  private static Session mailSession() {
    Properties properties = new Properties();
    properties.put("mail.imaps.ssl.socketFactory", tls);
    properties.put("mail.imaps.ssl.checkserveridentity", "true");
    properties.put("mail.smtp.ssl.socketFactory", tls);
    properties.put("mail.smtp.ssl.checkserveridentity", "true");
    properties.put("mail.smtp.starttls.enable", "true");
    properties.put("mail.smtp.starttls.required", "true");
    properties.put("mail.smtp.auth", "true");
    properties.put("mail.smtp.host", HOST);
    properties.put("mail.smtp.port", System.getProperty("dovecot.smtp.port", "12587"));
    return Session.getInstance(properties);
  }

  /**
   * An IMAPS store for a user.
   *
   * @param user the login
   * @param variable the password's variable
   * @return the connected store
   * @throws MessagingException when the login fails
   */
  private static Store store(String user, String variable) throws MessagingException {
    Store store = mailSession().getStore("imaps");
    store.connect(HOST, Integer.parseInt(System.getProperty("dovecot.imap.port", "12993")), user, password(variable));
    return store;
  }

  /**
   * Creates folders on alice's account, their parent first.
   *
   * @param folders the folders
   * @throws MessagingException when a folder cannot be created
   */
  private static void createFolders(String... folders) throws MessagingException {
    try (Store store = store(ALICE, "ALICE_PASSWORD")) {
      Folder root = store.getFolder(ROOT);
      if (!root.exists()) {
        root.create(Folder.HOLDS_MESSAGES | Folder.HOLDS_FOLDERS);
      }
      for (String name : folders) {
        Folder folder = store.getFolder(name);
        if (!folder.exists()) {
          assertTrue(folder.create(Folder.HOLDS_MESSAGES), name + " created");
        }
      }
    }
  }

  /**
   * Deletes every S5IT message of a folder.
   *
   * @param store the store
   * @param name the folder
   * @throws MessagingException when the folder cannot be read
   */
  private static void purge(Store store, String name) throws MessagingException {
    Folder folder = store.getFolder(name);
    folder.open(Folder.READ_WRITE);
    for (Message message : folder.getMessages()) {
      String subject = message.getSubject();
      if (subject != null && (subject.contains("S5IT") || subject.startsWith("Out of office"))) {
        message.setFlag(Flags.Flag.DELETED, true);
      }
    }
    folder.close(true);
  }

  /**
   * Sends one mail from bob to alice through the rig's submission service.
   *
   * @param subject the subject
   * @throws MessagingException when the submission fails
   */
  private static void send(String subject) throws MessagingException {
    MimeMessage message = new MimeMessage(mailSession());
    message.setFrom(new InternetAddress(BOB));
    message.setRecipients(Message.RecipientType.TO, new Address[] { new InternetAddress(ALICE) });
    message.setSubject(subject, "UTF-8");
    message.setText("S5 certification mail.\r\n", "UTF-8");
    Transport.send(message, new Address[] { new InternetAddress(ALICE) }, BOB, password("BOB_PASSWORD"));
  }

  /**
   * Sends one mail and waits for alice's automatic replies to reach bob.
   *
   * @param subject the subject
   * @return the replies bob received, bodies loaded
   * @throws Exception when the rig cannot be reached
   */
  private Message[] sendAndAwaitReply(String subject) throws Exception {
    send(subject);
    long end = System.currentTimeMillis() + WAIT_MILLIS;
    List<Message> replies = List.of();
    while (System.currentTimeMillis() < end && replies.isEmpty()) {
      Thread.sleep(1000);
      replies = repliesToBob();
    }
    Thread.sleep(3000);
    return repliesToBob().toArray(new Message[0]);
  }

  /**
   * Alice's automatic replies in bob's INBOX, loaded so they outlive the store.
   *
   * @return the replies
   * @throws MessagingException when the INBOX cannot be read
   * @throws IOException when a body cannot be read
   */
  private static List<Message> repliesToBob() throws MessagingException, IOException {
    List<Message> replies = new ArrayList<>();
    try (Store store = store(BOB, "BOB_PASSWORD")) {
      Folder inbox = store.getFolder("INBOX");
      inbox.open(Folder.READ_ONLY);
      for (Message message : inbox.getMessages()) {
        Address[] from = message.getFrom();
        if (from != null && from.length > 0 && ALICE.equalsIgnoreCase(((InternetAddress) from[0]).getAddress())) {
          replies.add(new MimeMessage((MimeMessage) message));
        }
      }
      inbox.close(false);
    }
    return replies;
  }

  /**
   * The text of a reply.
   *
   * @param message the reply
   * @return its text
   * @throws Exception when it cannot be read
   */
  private static String bodyOf(Message message) throws Exception {
    Object content = message.getContent();
    if (content instanceof String text) {
      return text;
    }
    try (InputStream in = message.getInputStream()) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  /**
   * The messages of a folder whose subject holds a marker.
   *
   * @param folderName the folder
   * @param marker the marker
   * @param keep whether to return detached copies with their flags
   * @return the matches' flags holders
   * @throws MessagingException when the folder cannot be read
   */
  private static List<Message> find(String folderName, String marker, boolean keep) throws MessagingException {
    List<Message> found = new ArrayList<>();
    try (Store store = store(ALICE, "ALICE_PASSWORD")) {
      Folder folder = store.getFolder(folderName);
      if (!folder.exists()) {
        return found;
      }
      folder.open(Folder.READ_ONLY);
      for (Message message : folder.getMessages()) {
        String subject = message.getSubject();
        if (subject != null && subject.contains(marker)) {
          found.add(keep ? detached(message) : message);
        }
      }
      folder.close(false);
    }
    return found;
  }

  /**
   * A copy of a message that keeps its flags after the store is closed.
   *
   * @param message the message
   * @return the copy
   * @throws MessagingException when it cannot be read
   */
  private static Message detached(Message message) throws MessagingException {
    Flags flags = message.getFlags();
    MimeMessage copy = new MimeMessage((MimeMessage) message);
    copy.setFlags(flags, true);
    return copy;
  }

  /**
   * Waits for exactly one message with a marker in a folder.
   *
   * @param folder the folder
   * @param marker the marker
   * @return the message, with its flags
   * @throws Exception when it does not arrive
   */
  private static Message awaitIn(String folder, String marker) throws Exception {
    List<Message> found = awaitAll(folder, marker, 1);
    assertEquals(1, found.size(), marker + " in " + folder);
    return found.get(0);
  }

  /**
   * Waits until a folder holds at least {@code count} messages with a marker.
   *
   * @param folder the folder
   * @param marker the marker
   * @param count the count waited for
   * @return what the folder holds then, flags kept
   * @throws Exception when the rig cannot be reached
   */
  private static List<Message> awaitAll(String folder, String marker, int count) throws Exception {
    long end = System.currentTimeMillis() + WAIT_MILLIS;
    List<Message> found = find(folder, marker, true);
    while (found.size() < count && System.currentTimeMillis() < end) {
      Thread.sleep(1000);
      found = find(folder, marker, true);
    }
    return found;
  }

  /**
   * A TLS layer trusting the given PEM certificates only.
   *
   * @param pems the certificates' paths
   * @return the factory
   * @throws IOException when the file cannot be read
   * @throws GeneralSecurityException when it is not a certificate
   */
  private static SSLSocketFactory socketFactory(String... pems) throws IOException, GeneralSecurityException {
    KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
    trust.load(null, null);
    for (int i = 0; i < pems.length; i++) {
      try (InputStream in = Files.newInputStream(Path.of(pems[i].trim()))) {
        trust.setCertificateEntry("rig" + i, CertificateFactory.getInstance("X.509").generateCertificate(in));
      }
    }
    TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(trust);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, factory.getTrustManagers(), null);
    return context.getSocketFactory();
  }
}
