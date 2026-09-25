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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.mail.PasswordAuthentication;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * An in-JVM ManageSieve server (RFC 5804 subset) for tests: a real TCP socket, a real
 * {@code STARTTLS} upgrade with a test certificate, scripted capabilities and refusals,
 * an in-memory script store with one active script, and a record of every command it
 * received — so a test asserts on what the client actually sent over the wire, not on
 * what a mock was called with.
 * <p>
 * The certificate is {@code src/test/resources/sieve/fake-managesieve.p12}, a
 * self-signed EC key for {@code localhost} and {@code 127.0.0.1}, generated once with
 * {@code keytool -genkeypair -alias fake-managesieve -keyalg EC -groupname secp256r1
 * -validity 36500 -dname "CN=localhost, O=eXo test fixture"
 * -ext "SAN=dns:localhost,ip:127.0.0.1" -storetype PKCS12}, password
 * {@value #KEYSTORE_PASSWORD}; {@code fake-managesieve-other-host.p12} is the same for
 * {@code other.example} only, so that a client trusting it still has to fail the
 * host-name check. They protect nothing: they only let the client's TLS and host-name
 * checks run for real. {@link #clientTlsFactory()} trusts the first.
 * <p>
 * By default the server behaves like the Stalwart v0.11.8 the plans observed: before TLS
 * it offers {@code STARTTLS} and SASL {@code OAUTHBEARER} only, after TLS it re-issues
 * its capabilities with SASL {@code PLAIN OAUTHBEARER}, a {@code VERSION}, and a
 * {@code SIEVE} line with {@code include}.
 */
public final class FakeManageSieveServer implements AutoCloseable {

  /** The login the server accepts. */
  public static final String   LOGIN             = "alice@stalwart.local";

  /** The password the server accepts. */
  public static final String   PASSWORD          = "alice-s3cret";

  /** The test keystore's password. */
  static final String          KEYSTORE_PASSWORD = "fake-managesieve";

  /** The keystore whose certificate names {@code localhost} and {@code 127.0.0.1}. */
  static final String          LOCALHOST_KEYSTORE  = "/sieve/fake-managesieve.p12";

  /** A keystore whose certificate names only {@code other.example}. */
  static final String          OTHER_HOST_KEYSTORE = "/sieve/fake-managesieve-other-host.p12";

  /** The SIEVE line of Stalwart v0.11.8, as the plans recorded it. */
  public static final String   STALWART_SIEVE    = "body comparator-elbonia comparator-i;ascii-casemap comparator-i;ascii-numeric "
      + "comparator-i;octet date duplicate editheader enclose encoded-character enotify envelope envelope-deliverby "
      + "envelope-dsn environment ereject extlists extracttext fcc fileinto foreverypart ihave imap4flags imapsieve "
      + "include index mailbox mailboxid mboxmetadata mime reject regex relational replace servermetadata "
      + "spamtest spamtestplus special-use subaddress vacation vacation-seconds variables virustest";

  private final ServerSocket   serverSocket;

  private final SSLContext     tls;

  private final Thread         acceptor;

  private final List<Socket>   connections       = new ArrayList<>();

  private final List<String>   commands          = new ArrayList<>();

  private final Map<String, String> scripts      = new LinkedHashMap<>();

  private final Map<String, String> refusals     = new HashMap<>();

  private String               active;

  private volatile boolean     offerStarttls     = true;

  private volatile boolean     reissueAfterTls   = true;

  private volatile boolean     advertiseVersion  = true;

  private volatile boolean     silent;

  private volatile boolean     injectAfterStarttls;

  private volatile String      postTlsSasl       = "PLAIN OAUTHBEARER";

  private volatile String      sieveExtensions   = STALWART_SIEVE;

  private volatile String      password          = PASSWORD;

  private volatile String      rawGetScriptAnswer;

  private volatile long        trickleMillis;

  private int                  authentications;

  /**
   * Starts a server on an ephemeral loopback port, its certificate naming
   * {@code localhost}.
   *
   * @throws Exception when the socket or the TLS context cannot be created
   */
  public FakeManageSieveServer() throws Exception {
    this(LOCALHOST_KEYSTORE);
  }

  /**
   * Starts a server on an ephemeral loopback port with the certificate of a keystore.
   *
   * @param keystore the classpath keystore
   * @throws Exception when the socket or the TLS context cannot be created
   */
  public FakeManageSieveServer(String keystore) throws Exception {
    tls = serverTls(keystore);
    serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    acceptor = new Thread(this::acceptLoop, "fake-managesieve-acceptor");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  /**
   * The port the server listens on.
   *
   * @return the port
   */
  public int getPort() {
    return serverSocket.getLocalPort();
  }

  /**
   * A TLS socket factory trusting the fixture's certificate, and nothing else.
   *
   * @return the factory
   * @throws Exception when the keystore cannot be read
   */
  public static SSLSocketFactory clientTlsFactory() throws Exception {
    return clientTlsFactory(LOCALHOST_KEYSTORE);
  }

  /**
   * A TLS socket factory trusting the certificate of one keystore, and nothing else.
   *
   * @param keystore the classpath keystore
   * @return the factory
   * @throws Exception when the keystore cannot be read
   */
  public static SSLSocketFactory clientTlsFactory(String keystore) throws Exception {
    TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(keyStore(keystore));
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, trust.getTrustManagers(), null);
    return context.getSocketFactory();
  }

  /**
   * Connects an authenticated client to this server as {@link #LOGIN}.
   *
   * @return the client
   * @throws Exception when connecting or authenticating fails
   */
  public ManageSieveClient authenticatedClient() throws Exception {
    ManageSieveClient client = ManageSieveClient.connect("localhost", getPort(), clientTlsFactory(), 5000, 5000);
    client.authenticatePlain(new PasswordAuthentication(LOGIN, PASSWORD));
    return client;
  }

  /**
   * Whether {@code STARTTLS} is offered before TLS.
   *
   * @param offer false to model a server that only speaks in clear
   * @return this server
   */
  public FakeManageSieveServer offerStarttls(boolean offer) {
    this.offerStarttls = offer;
    return this;
  }

  /**
   * The SASL mechanisms advertised after TLS.
   *
   * @param mechanisms the space-separated list
   * @return this server
   */
  public FakeManageSieveServer postTlsSasl(String mechanisms) {
    this.postTlsSasl = mechanisms;
    return this;
  }

  /**
   * The {@code SIEVE} capability line.
   *
   * @param extensions the space-separated extensions
   * @return this server
   */
  public FakeManageSieveServer sieveExtensions(String extensions) {
    this.sieveExtensions = extensions;
    return this;
  }

  /**
   * Whether {@code VERSION} is advertised, which is what offers {@code CHECKSCRIPT}.
   *
   * @param advertise false to model a pre-RFC 5804 server
   * @return this server
   */
  public FakeManageSieveServer advertiseVersion(boolean advertise) {
    this.advertiseVersion = advertise;
    return this;
  }

  /**
   * Whether the capabilities are re-issued after TLS, as RFC 5804 §2.2 requires.
   *
   * @param reissue false to model a server that does not
   * @return this server
   */
  public FakeManageSieveServer reissueAfterTls(boolean reissue) {
    this.reissueAfterTls = reissue;
    return this;
  }

  /**
   * Makes the server accept connections and never answer.
   *
   * @return this server
   */
  public FakeManageSieveServer silent() {
    this.silent = true;
    return this;
  }

  /**
   * Makes the server push bytes right after its {@code STARTTLS} OK, in clear — the shape
   * of a command-injection attempt against the TLS upgrade.
   *
   * @return this server
   */
  public FakeManageSieveServer injectAfterStarttls() {
    this.injectAfterStarttls = true;
    return this;
  }

  /**
   * The password the server accepts from now on.
   *
   * @param accepted the password
   * @return this server
   */
  public FakeManageSieveServer acceptPassword(String accepted) {
    this.password = accepted;
    return this;
  }

  /**
   * Replaces the answer to every {@code GETSCRIPT} with raw bytes, to test the client's
   * limits.
   *
   * @param raw the raw answer, CRLFs included
   * @return this server
   */
  public FakeManageSieveServer rawGetScriptAnswer(String raw) {
    this.rawGetScriptAnswer = raw;
    return this;
  }

  /**
   * Makes every {@code GETSCRIPT} answer a literal one octet at a time, one octet per
   * interval, forever — each read succeeds, the answer never ends.
   *
   * @param intervalMillis the pause between two octets
   * @return this server
   */
  public FakeManageSieveServer trickleGetScript(long intervalMillis) {
    this.trickleMillis = intervalMillis;
    return this;
  }

  /**
   * Makes a command answer a fixed line instead of executing.
   *
   * @param command the command, e.g. {@code CHECKSCRIPT}
   * @param answer the full final line, e.g. {@code NO "line 1: error"}
   * @return this server
   */
  public synchronized FakeManageSieveServer refuse(String command, String answer) {
    refusals.put(command.toUpperCase(Locale.ROOT), answer);
    return this;
  }

  /**
   * Stores a script directly, as another client would have.
   *
   * @param name the name
   * @param content the text
   * @param makeActive whether it becomes the active script
   * @return this server
   */
  public synchronized FakeManageSieveServer script(String name, String content, boolean makeActive) {
    scripts.put(name, content);
    if (makeActive) {
      active = name;
    }
    return this;
  }

  /**
   * The stored scripts.
   *
   * @return a copy, name to text
   */
  public synchronized Map<String, String> getScripts() {
    return new LinkedHashMap<>(scripts);
  }

  /**
   * The active script.
   *
   * @return its name, or null
   */
  public synchronized String getActive() {
    return active;
  }

  /**
   * Every command received, in order, as {@code VERB arg1 arg2} with the
   * {@code AUTHENTICATE} initial response redacted.
   *
   * @return a copy
   */
  public synchronized List<String> getCommands() {
    return new ArrayList<>(commands);
  }

  /**
   * The commands received with a given verb.
   *
   * @param verb the verb
   * @return the matching commands
   */
  public synchronized List<String> getCommands(String verb) {
    return commands.stream().filter(command -> command.equals(verb) || command.startsWith(verb + " ")).toList();
  }

  /**
   * How many {@code AUTHENTICATE} commands the server received.
   *
   * @return the count
   */
  public synchronized int getAuthentications() {
    return authentications;
  }

  /**
   * Stops the server and drops every connection.
   */
  @Override
  public void close() {
    try {
      serverSocket.close();
    } catch (IOException e) {
      // Closing anyway.
    }
    synchronized (connections) {
      for (Socket socket : connections) {
        try {
          socket.close();
        } catch (IOException e) {
          // Closing anyway.
        }
      }
    }
  }

  /**
   * Accepts connections until closed, one handler thread each.
   */
  private void acceptLoop() {
    while (!serverSocket.isClosed()) {
      try {
        Socket socket = serverSocket.accept();
        synchronized (connections) {
          connections.add(socket);
        }
        Thread handler = new Thread(() -> serve(socket), "fake-managesieve-connection");
        handler.setDaemon(true);
        handler.start();
      } catch (IOException e) {
        return;
      }
    }
  }

  /**
   * Serves one connection.
   *
   * @param plain the accepted socket
   */
  private void serve(Socket plain) {
    Socket socket = plain;
    try {
      if (silent) {
        plain.getInputStream().read();
        return;
      }
      InputStream in = new BufferedInputStream(socket.getInputStream());
      OutputStream out = socket.getOutputStream();
      boolean secure = false;
      boolean authenticated = false;
      send(out, capabilities(false) + "OK \"Fake ManageSieve ready\"\r\n");
      while (true) {
        List<String> args = readCommand(in);
        if (args.isEmpty()) {
          continue;
        }
        String verb = args.get(0).toUpperCase(Locale.ROOT);
        record(verb, args);
        if ("GETSCRIPT".equals(verb) && trickleMillis > 0) {
          send(out, "{1000000}\r\n");
          while (true) {
            send(out, "x");
            Thread.sleep(trickleMillis);
          }
        }
        String refusal = refusal(verb);
        if (refusal != null) {
          send(out, refusal + "\r\n");
          continue;
        }
        switch (verb) {
        case "STARTTLS" -> {
          if (secure || !offerStarttls) {
            send(out, "NO \"STARTTLS not available\"\r\n");
            continue;
          }
          send(out, "OK \"Begin TLS negotiation now\"\r\n" + (injectAfterStarttls ? "OK \"injected\"\r\n" : ""));
          SSLSocket upgraded = (SSLSocket) tls.getSocketFactory().createSocket(plain, null, plain.getPort(), false);
          upgraded.setUseClientMode(false);
          upgraded.startHandshake();
          socket = upgraded;
          synchronized (connections) {
            connections.add(upgraded);
          }
          in = new BufferedInputStream(socket.getInputStream());
          out = socket.getOutputStream();
          secure = true;
          if (reissueAfterTls) {
            send(out, capabilities(true) + "OK \"TLS negotiation successful\"\r\n");
          }
        }
        case "CAPABILITY" -> send(out, capabilities(secure) + "OK\r\n");
        case "AUTHENTICATE" -> {
          authenticated = secure && authenticate(args);
          send(out, authenticated ? "OK \"Authenticated\"\r\n" : "NO \"Authentication failed\"\r\n");
        }
        case "LOGOUT" -> {
          send(out, "OK \"Bye\"\r\n");
          return;
        }
        default -> send(out, authenticated ? execute(verb, args) : "NO \"Authenticate first\"\r\n");
        }
      }
    } catch (IOException e) {
      // The client closed the connection or broke the protocol.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      try {
        socket.close();
      } catch (IOException e) {
        // Closing anyway.
      }
    }
  }

  /**
   * Executes a script command on the in-memory store.
   *
   * @param verb the command
   * @param args the arguments, verb included
   * @return the full answer
   */
  private synchronized String execute(String verb, List<String> args) {
    switch (verb) {
    case "LISTSCRIPTS": {
      StringBuilder answer = new StringBuilder();
      for (String name : scripts.keySet()) {
        answer.append(quote(name)).append(name.equals(active) ? " ACTIVE" : "").append("\r\n");
      }
      return answer.append("OK\r\n").toString();
    }
    case "GETSCRIPT": {
      if (rawGetScriptAnswer != null) {
        return rawGetScriptAnswer;
      }
      String content = scripts.get(args.get(1));
      if (content == null) {
        return "NO (NONEXISTENT) \"There is no script by that name\"\r\n";
      }
      return "{" + content.getBytes(StandardCharsets.UTF_8).length + "}\r\n" + content + "\r\nOK\r\n";
    }
    case "PUTSCRIPT":
      scripts.put(args.get(1), args.get(2));
      return "OK\r\n";
    case "CHECKSCRIPT":
      return advertiseVersion ? "OK\r\n" : "NO \"Unknown command\"\r\n";
    case "SETACTIVE": {
      String name = args.get(1);
      if (name.isEmpty()) {
        active = null;
        return "OK\r\n";
      }
      if (!scripts.containsKey(name)) {
        return "NO (NONEXISTENT) \"There is no script by that name\"\r\n";
      }
      active = name;
      return "OK\r\n";
    }
    case "DELETESCRIPT": {
      String name = args.get(1);
      if (name.equals(active)) {
        return "NO (ACTIVE) \"You may not delete an active script\"\r\n";
      }
      if (scripts.remove(name) == null) {
        return "NO (NONEXISTENT) \"There is no script by that name\"\r\n";
      }
      return "OK\r\n";
    }
    default:
      return "NO \"Unknown command\"\r\n";
    }
  }

  /**
   * Checks an {@code AUTHENTICATE "PLAIN" "<base64>"} command.
   *
   * @param args the arguments
   * @return true when the credentials are the accepted ones
   */
  private synchronized boolean authenticate(List<String> args) {
    authentications++;
    if (args.size() < 3 || !"PLAIN".equalsIgnoreCase(args.get(1))) {
      return false;
    }
    String decoded = new String(Base64.getDecoder().decode(args.get(2)), StandardCharsets.UTF_8);
    return decoded.equals("\0" + LOGIN + "\0" + password);
  }

  /**
   * The configured refusal of a command.
   *
   * @param verb the command
   * @return the refusal line, or null
   */
  private synchronized String refusal(String verb) {
    return refusals.get(verb);
  }

  /**
   * Records a command, the credentials redacted.
   *
   * @param verb the verb
   * @param args the arguments, verb included
   */
  private synchronized void record(String verb, List<String> args) {
    if ("AUTHENTICATE".equals(verb)) {
      commands.add("AUTHENTICATE " + (args.size() > 1 ? args.get(1) : "") + " <redacted>");
    } else {
      commands.add(String.join(" ", args).replaceFirst("^\\S+", verb));
    }
  }

  /**
   * The capability lines, before or after TLS.
   *
   * @param secure whether TLS is up
   * @return the lines, without the final OK
   */
  private String capabilities(boolean secure) {
    StringBuilder lines = new StringBuilder("\"IMPLEMENTATION\" \"Fake ManageSieve\"\r\n");
    lines.append("\"SASL\" ").append(quote(secure ? postTlsSasl : "OAUTHBEARER")).append("\r\n");
    lines.append("\"SIEVE\" ").append(quote(sieveExtensions)).append("\r\n");
    if (!secure && offerStarttls) {
      lines.append("\"STARTTLS\"\r\n");
    }
    if (advertiseVersion) {
      lines.append("\"VERSION\" \"1.0\"\r\n");
    }
    return lines.toString();
  }

  /**
   * Reads one command: its tokens up to CRLF, literals included.
   *
   * @param in the input
   * @return the tokens, verb first
   * @throws IOException when the connection fails or the command is malformed
   */
  private static List<String> readCommand(InputStream in) throws IOException {
    List<String> tokens = new ArrayList<>();
    ByteArrayOutputStream atom = null;
    while (true) {
      int c = next(in);
      if (c == '\r') {
        next(in);
        if (atom != null) {
          tokens.add(atom.toString(StandardCharsets.UTF_8));
        }
        return tokens;
      }
      if (c == ' ') {
        if (atom != null) {
          tokens.add(atom.toString(StandardCharsets.UTF_8));
          atom = null;
        }
      } else if (c == '"' && atom == null) {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        for (int q = next(in); q != '"'; q = next(in)) {
          value.write(q == '\\' ? next(in) : q);
        }
        tokens.add(value.toString(StandardCharsets.UTF_8));
      } else if (c == '{' && atom == null) {
        StringBuilder count = new StringBuilder();
        for (int d = next(in); d != '}'; d = next(in)) {
          count.append((char) d);
        }
        if (!count.toString().endsWith("+")) {
          throw new IOException("The client sent a synchronizing literal");
        }
        next(in);
        next(in);
        byte[] octets = in.readNBytes(Integer.parseInt(count.substring(0, count.length() - 1)));
        tokens.add(new String(octets, StandardCharsets.UTF_8));
      } else {
        if (atom == null) {
          atom = new ByteArrayOutputStream();
        }
        atom.write(c);
      }
    }
  }

  /**
   * Reads one octet.
   *
   * @param in the input
   * @return the octet
   * @throws IOException at the end of the stream
   */
  private static int next(InputStream in) throws IOException {
    int c = in.read();
    if (c < 0) {
      throw new EOFException();
    }
    return c;
  }

  /**
   * Writes an answer.
   *
   * @param out the output
   * @param text the answer
   * @throws IOException when the connection fails
   */
  private static void send(OutputStream out, String text) throws IOException {
    out.write(text.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  /**
   * Quotes a value as a ManageSieve quoted string.
   *
   * @param value the value
   * @return the quoted string
   */
  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /**
   * The server's TLS context, from a test keystore.
   *
   * @param keystore the classpath keystore
   * @return the context
   * @throws Exception when the keystore cannot be read
   */
  private static SSLContext serverTls(String keystore) throws Exception {
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(keyStore(keystore), KEYSTORE_PASSWORD.toCharArray());
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keys.getKeyManagers(), null, null);
    return context;
  }

  /**
   * A test keystore.
   *
   * @param keystore the classpath keystore
   * @return the loaded keystore
   * @throws Exception when it cannot be read
   */
  private static KeyStore keyStore(String keystore) throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream in = FakeManageSieveServer.class.getResourceAsStream(keystore)) {
      store.load(in, KEYSTORE_PASSWORD.toCharArray());
    }
    return store;
  }
}
